package dev.andymacdonald.chaos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.function.Supplier;
import java.util.stream.Stream;

import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import dev.andymacdonald.chaos.strategy.ChaosStrategy;
import dev.andymacdonald.config.ChaosProxyConfigurationService;
import lombok.Getter;

@Service
public class ChaosService
{
    @Getter
    private ChaosStrategy activeChaosStrategy = ChaosStrategy.NO_CHAOS;

    @Getter
    private boolean tracingHeaders;

    private final Logger log = LoggerFactory.getLogger(ChaosService.class);
    private final ChaosProxyConfigurationService chaosProxyConfigurationService;
    private final DelayService delayService;

    public ChaosService(ChaosProxyConfigurationService chaosProxyConfigurationService, DelayService delayService)
    {
        this.chaosProxyConfigurationService = chaosProxyConfigurationService;
        this.delayService = delayService;
        if (chaosProxyConfigurationService.getInitialChaosStrategy() != null)
        {
            this.activeChaosStrategy = chaosProxyConfigurationService.getInitialChaosStrategy();
        }
        this.tracingHeaders = chaosProxyConfigurationService.isTracingHeaders();
        log.info("Initial active chaos strategy: {}", this.activeChaosStrategy);
    }

    public ChaosResult processRequestAndApplyChaos(Supplier<ResponseEntity<byte[]>> responseEntity, String uri) throws InterruptedException
    {
        ChaosStrategy chaosStrategy;
        if (Math.random() < 0.5 && shouldBeChaotic(uri)) {
            if (Math.random() < 0.5) {
                chaosStrategy = ChaosStrategy.DELAY_REQUEST;
            } else {
                chaosStrategy = ChaosStrategy.INSTANT_REQUEST_DELAY_RESPONSE;
            }
        } else {
            chaosStrategy = ChaosStrategy.NO_CHAOS;
        }
        log.info("Chaos: {}", chaosStrategy);

        int chaosStatusCode;
        ResponseEntity<byte[]> chaosResponseEntity;
        Long delayedBy = 0L;

        switch (chaosStrategy)
        {
            case NO_CHAOS:
                chaosResponseEntity = responseEntity.get();
                chaosStatusCode = chaosResponseEntity.getStatusCodeValue();
                break;
            case INTERNAL_SERVER_ERROR:
                int internalServerError = HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
                chaosResponseEntity = ResponseEntity.status(internalServerError).build();
                chaosStatusCode = internalServerError;
                break;
            case BAD_REQUEST:
                int badRequest = HttpServletResponse.SC_BAD_REQUEST;
                chaosResponseEntity = ResponseEntity.status(badRequest).build();
                chaosStatusCode = badRequest;
                break;
            case DELAY_REQUEST:
                delayedBy = delayRequestBasedOnConfiguration();
                Thread.currentThread().interrupt();
                throw new InterruptedException("Drop the bass");
                // this.chaosResponseEntity = responseEntity.get();
                // this.chaosStatusCode = this.chaosResponseEntity.getStatusCodeValue();
                // break;
            case INSTANT_REQUEST_DELAY_RESPONSE:
                chaosResponseEntity = responseEntity.get();
                delayedBy = delayRequestBasedOnConfiguration();
                chaosStatusCode = chaosResponseEntity.getStatusCodeValue();
                Thread.currentThread().interrupt();
                throw new InterruptedException("Drop the bass");
                // break;
            case RANDOM_HAVOC:
                delayedBy = randomlyDelayRequest();
                chaosResponseEntity = responseEntity.get();
                chaosStatusCode = getRandomStatusCodeFavouringOk();
                log.info("Responding with status code: {}", chaosStatusCode);
                break;
            default:
                throw new UnsupportedOperationException("Unsupported chaos strategy: " + chaosStrategy);
        }
        return ChaosResult.builder()
                          .chaosStatusCode(chaosStatusCode)
                          .chaosResponseEntity(chaosResponseEntity)
                          .delayedBy(delayedBy)
                          .build();
    }

    private boolean shouldBeChaotic(String uri)
    {
        return Stream.of("/commitTransaction")
                     .anyMatch(uri::contains);
    }

    private Optional<ChaosStrategy> loadRuntimeStrategy()
    {
        try {
            List<String> configLines = Files.readAllLines(Paths.get("runtime.config"));
            String loadedStrategyLine = configLines.get(0);
            ChaosStrategy loadedStrategy = ChaosStrategy.valueOf(loadedStrategyLine);
            return Optional.of(loadedStrategy);
        } catch (IOException e) {
            log.info("Missing runtime config", e);
        }
        return Optional.empty();
    }

    public synchronized void setActiveChaosStrategy(ChaosStrategy chaosStrategy)
    {
        this.activeChaosStrategy = chaosStrategy;
        log.info("Active chaos strategy: {}", this.activeChaosStrategy);
    }

    private Long delayRequestBasedOnConfiguration() throws InterruptedException
    {
        if (chaosProxyConfigurationService.isFixedDelayPeriod())
        {
            delayService.delay(chaosProxyConfigurationService.getDelayTimeSeconds());
            return chaosProxyConfigurationService.getDelayTimeSeconds();
        }
        else
        {
            return randomlyDelayRequest();
        }
    }

    private Long randomlyDelayRequest() throws InterruptedException
    {
        Long delaySeconds = 0L;
        if (randomBoolean())
        {
            delaySeconds = Integer.toUnsignedLong(new Random().nextInt(chaosProxyConfigurationService.getRandomDelayMaxSeconds()));
            log.info("Delaying response by {} seconds", delaySeconds);
            delayService.delay(delaySeconds);
        }
        return delaySeconds;
    }

    private int getRandomStatusCodeFavouringOk()
    {
        if (randomBoolean())
        {
            return HttpServletResponse.SC_OK;
        }
        return VALID_STATUS_CODES[new Random().nextInt(VALID_STATUS_CODES.length - 1)];
    }

    private static boolean randomBoolean()
    {
        return !(Math.random() > 0.75);
    }

    private static final int[] VALID_STATUS_CODES = new int[]{100, 101, 200, 201, 202, 203, 204, 205, 206, 300, 301, 302, 303, 304, 305, 307, 400, 401, 402, 403, 404, 405, 406, 407, 408, 409, 410, 411, 412, 413, 414, 415, 416, 417, 500, 501, 502, 503, 504, 505};

}
