package com.lectura.backend.service.impl;

import com.lectura.backend.entity.*;
import com.lectura.backend.model.*;
import com.lectura.backend.repository.PublicationRepository;
import com.lectura.backend.repository.PublisherRepository;
import com.lectura.backend.service.CantookServiceAPI;
import com.lectura.backend.service.ICantookService;
import com.lectura.backend.service.IWooCommerceService;
import com.lectura.backend.service.WooCommerceAPI;
import com.lectura.backend.util.Descriptions;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.transaction.*;
import javax.ws.rs.BadRequestException;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.WebApplicationException;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static java.util.Arrays.asList;

@ApplicationScoped
public class WooCommerceService implements IWooCommerceService {
    private static final Logger logger = Logger.getLogger(WooCommerceService.class);

    @ConfigProperty(name = "libranda.exchange-rate", defaultValue = "6.96")
    static Double exchangeRate;

    @ConfigProperty(name = "libranda.sale-state", defaultValue = "test")
    String saleState;

    @Inject
    @RestClient
    WooCommerceAPI wooCommerceAPI;

    @Inject
    @RestClient
    CantookServiceAPI cantookServiceAPI;

    @Inject
    PublicationRepository repository;

    @Inject
    PublisherRepository publisherRepository;

    @Inject
    UserTransaction transaction;

    @Inject
    ICantookService cantookService;

    @Override
    public void synchronization(LocalDateTime dateTime) throws Exception {
        if (!synchronizeParameters()) {
            throw new Exception("Error ocurring in Synchronization of parameters.");
        }

        transaction.begin();
        var publications = repository.findToSynchronize();
        AtomicInteger quantityErrors = new AtomicInteger();
        try {
            logger.info("# Publications to publish: " + publications.size());
            publications.stream()
                    .forEach(p -> {
                        try {
                            sendProductToWoocommerce(p);
                        } catch (Exception ex) {
                            quantityErrors.getAndIncrement();
                            logger.error("Error on publishing product: " + p.getId() + " - Error: " + ex.getMessage(), ex);
                        }
                    });
        } catch (Exception ex) {
            logger.error("Total Errors: " + quantityErrors.get());
            logger.error(ex.getMessage(), ex);
            throw ex;
        } finally {
            transaction.commit();
        }
    }

    @Override
    public SimulateSaleResponse simulateSale(Long productId, Double price) throws Exception {
        transaction.begin();
        try {
            var response = SimulateSaleResponse.builder();
            var publication = repository.findByProductId(productId);

            if (Objects.nonNull(publication) && !publication.getId().isEmpty()) {
                Price publicationPrice = publication.getPrice();
                try {
                    var validSale = cantookServiceAPI.simulateSale(publication.getIsbn(),
                            Descriptions.getFormats(publication.getProductFormDetail()).get(0),
                            publicationPrice.getIntegerPriceAmount(), Descriptions.getProtection(publication.getTechnicalProtection()),
                            publicationPrice.getCountryCode(), publicationPrice.getCurrencyCode(), publicationPrice.getType());
                    if (validSale.getStatus() == 200) {
                        return response.ok(true)
                                .message(validSale.readEntity(String.class))
                                .productId(publication.getProductId())
                                .price(calculatePrice(publicationPrice)).build();
                    }
                } catch (WebApplicationException ex) {
                    if (ex.getResponse().getStatus() == 400) {
                        logger.warn("Update the publication");
                        cantookService.synchronize(publication.getIsbn());
                        publication = repository.findByProductId(productId);
                        sendProductToWoocommerce(publication);
                        return response.ok(false)
                                .message("Los datos del producto fueron actualizados.")
                                .productId(publication.getProductId())
                                .price(calculatePrice(publication.getPrice())).build();
                    } else {
                        throw ex;
                    }
                } catch (Exception ex) {
                    logger.warn("Error on calling Cantook API. " + ex.getMessage(), ex);
                    throw ex;
                }
            } else {
                throw new NotFoundException("No se pudo encontrar el producto ingresado, por favor comuniquese con el administrador.");
            }
        } catch (Exception ex) {
            logger.error("Error on simulate a Sale. " + ex.getMessage(), ex);
            transaction.rollback();
            throw ex;
        } finally {
            transaction.commit();
        }
        return null;
    }

    @Override
    public String registerSale(OrderDto order) throws Exception {
        transaction.begin();
        try {
            var publication = repository.findByProductId(order.getProductId());

            if (Objects.nonNull(publication) && !publication.getId().isEmpty()) {
                Price publicationPrice = publication.getPrice();
                try {
                    var request = SaleRequest.builder()
                            .cost(publicationPrice.getIntegerPriceAmount())
                            .country(publicationPrice.getCountryCode())
                            .format(Descriptions.getFormats(publication.getProductFormDetail()).get(0))
                            .customer_id(order.getUsername())
                            .price_type(publicationPrice.getType())
                            .protection(Descriptions.getProtection(publication.getTechnicalProtection()))
                            .currency(publicationPrice.getCurrencyCode())
                            .sale_state(saleState)
                            .transaction_id(order.getOrderId()).build();

                    var response = cantookServiceAPI.salePublication(publication.getIsbn(), request);
                    if (response.getStatus() == 201) {
                        var token = getUniqueToken();
                        var sale = new Sale();
                        sale.setCustomer(order.getUsername());
                        sale.setDateTime(LocalDateTime.now());
                        sale.setFormat(request.getFormat());
                        sale.setId(order.getOrderId());
                        sale.setPrice(publicationPrice.getPriceAmount());
                        sale.setSku(publication.getIsbn());
                        sale.setQuantity((short) 1);
                        sale.setCurrency(publicationPrice.getCurrencyCode());
                        sale.setDownloaded(false);
                        sale.setToken(token);

                        sale.persist();
                        return token;
                    } else {
                        throw new BadRequestException("No se puede registrar la venta por favor comuniquese con la Administración.");
                    }
                } catch (Exception ex) {
                    logger.error("Error on calling Cantook API. " + ex.getMessage(), ex);
                    throw ex;
                }
            } else {
                throw new NotFoundException("No se pudo encontrar el producto ingresado, por favor comuniquese con el administrador.");
            }
        } catch (Exception ex) {
            logger.error("Error on selling a publication  . " + ex.getMessage(), ex);
            transaction.rollback();
            throw ex;
        } finally {
            transaction.commit();
        }
    }

    @Override
    public URI getDownloadUrl(String token, String uname) throws SystemException, NotSupportedException, HeuristicRollbackException, HeuristicMixedException, RollbackException {
        transaction.begin();
        try {
            var saleOptional = Sale.findByToken(token);

            if (saleOptional.isPresent() && !saleOptional.get().getId().isEmpty()) {
                var sale = saleOptional.get();
                if (sale.getDateTime().plusMinutes(5).isAfter(LocalDateTime.now())) {
                    throw new BadRequestException("Tiempo expirado de descarga.");
                }
                try {
                    var response = cantookServiceAPI.getDownloadPublication(sale.getCustomer(), sale.getId(),
                            sale.getSku(), sale.getFormat(), uname);
                    if (response.getStatus() == 200) {
                        var uri = URI.create(response.readEntity(String.class));
                        sale.setDownloaded(true);
                        sale.persist();
                        return uri;
                    } else {
                        throw new BadRequestException("No se pudo obtener la URL de descarga del producto, por favor comuniquese con el Administrador.");
                    }
                } catch (Exception ex) {
                    logger.error("Error on calling Cantook API. " + ex.getMessage(), ex);
                    throw ex;
                }
            } else {
                throw new NotFoundException("No se pudo encontrar el registro de venta activo, por favor comuniquese con el Administrador.");
            }
        } catch (Exception ex) {
            logger.error("Error on selling a publication  . " + ex.getMessage(), ex);
            transaction.rollback();
            throw ex;
        } finally {
            logger.info("Transaction: " + transaction.getStatus());
            if (transaction.getStatus() != 6) {
                transaction.commit();
            }
        }
    }

    private void sendProductToWoocommerce(Publication publication) throws Exception {
        var price = publication.getPrice();
        if (Objects.nonNull(price) && !price.isMigrated()) {
            var updated = (Objects.isNull(price.getRole()) || price.getRole().equals(Byte.valueOf("14")));
            Double priceValue = calculatePrice(price);

            if (Objects.nonNull(publication.getProductId()) && publication.getProductId() > 0) {
                var productUpdate = UpdateProductRequest.builder()
                        .description(publication.getTextContent())
                        .short_description(publication.getAuthor())
                        .name(publication.getTitle())
                        .regular_price(String.valueOf(priceValue))
                        .categories(getCategories(publication.getSubjectBicCode()))
                        .tags(asList(new ItemDto(publication.getPublisher().getTagId(), null)))
                        .build();
                wooCommerceAPI.putProduct(publication.getProductId(), productUpdate);
                publication.setUpdated(true);
                repository.persist(publication);
            } else {
                var product = CreateProductRequest.builder()
                        .description(publication.getTextContent())
                        .short_description(publication.getAuthor())
                        .name(publication.getTitle())
                        .sku(publication.getIsbn())
                        .regular_price(String.valueOf(priceValue))
                        .type("simple")
                        .images(asList(new ImageDto(null, publication.getMedia().getPath())))
                        .categories(getCategories(publication.getSubjectBicCode()))
                        .tags(asList(new ItemDto(publication.getPublisher().getTagId(), null)))
                        .virtual(true)
                        .build();

                ProductDto response = wooCommerceAPI.postProduct(product);
                if (!price.getCurrencyCode().equals("BOB")) {
                    publication.setExchangeRate(exchangeRate);
                }
                price.setMigrated(true);
                publication.setUpdated(updated);
                publication.setProductId(response.getId());
                repository.persist(publication);
            }
        } else {
            logger.warn("The publication " + publication.getId() + " was not synchronized. " + publication);
        }
    }

    private Double calculatePrice(Price price) {
        Double priceValue = 0D;
        if (price.getCurrencyCode().equals("BOB")) {
            priceValue = price.getPriceAmount();
        } else {
            priceValue = (double) Math.round((price.getPriceAmount() * exchangeRate) * 100) / 100;
        }
        return priceValue;
    }

    private boolean synchronizeParameters() throws SystemException, NotSupportedException,
            HeuristicRollbackException, HeuristicMixedException, RollbackException {
        transaction.begin();
        try {
            synchronizeTags();
            var publications = repository.findToSynchronize();
            var bicCodes = publications.stream()
                    .map(p -> asList(StringUtils.split(p.getSubjectBicCode(), "|")))
                    .flatMap(Collection::stream)
                    .distinct()
                    .collect(Collectors.toList());
            synchronizeCategories(bicCodes);
        } catch (Exception ex) {
            logger.error(ex.getMessage(), ex);
            throw ex;
        } finally {
            transaction.commit();
        }
        return true;
    }

    private List<ItemDto> getCategories(String subjectsBIC) {
        return Arrays.stream(StringUtils.split(subjectsBIC, "|"))
                .map(s -> ((Category) Category.findById(s)).getCategoryId()).distinct()
                .map(c -> ItemDto.builder().id(c).build())
                .collect(Collectors.toList());
    }

    private void synchronizeTags() {
        var publishers = publisherRepository.findToSynchronize();
        logger.info("# Publishers to publish: " + publishers.size());
        try {
            publishers.stream()
                    .forEach(p -> {
                        var tag = ItemDto.builder()
                                .name(p.getName())
                                .build();
                        var result = wooCommerceAPI.postTag(tag);
                        p.setTagId(result.getId());
                    });
        } catch (Exception ex) {
            logger.error(ex.getMessage(), ex);
            throw ex;
        } finally {
            publisherRepository.persist(publishers);
        }
    }

    private void synchronizeCategories(List<String> bicCodes) {
        var subjects = Category.findToSynchronize(bicCodes);
        logger.info("# Categories: " + subjects.size());
        try {
            subjects.stream()
                    .forEach(p -> {
                        var category = ItemDto.builder()
                                .name(p.getDescription())
                                .build();
                        var result = wooCommerceAPI.postCategories(category);
                        p.setCategoryId(result.getId());
                        p.persist();
                    });
        } catch (Exception ex) {
            logger.error("Error on synchronization of Categories.", ex);
            throw ex;
        }
    }

    private String getUniqueToken() {
        StringBuilder token = new StringBuilder();
        long currentTimeInMilisecond = Instant.now().toEpochMilli();
        return token.append(currentTimeInMilisecond).append("-")
                .append(UUID.randomUUID()).toString();
    }
}
