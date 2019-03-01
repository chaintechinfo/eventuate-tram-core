package io.eventuate.tram.consumer.redis;

import io.eventuate.javaclient.commonimpl.JSonMapper;
import io.eventuate.tram.consumer.common.DuplicateMessageDetector;
import io.eventuate.tram.messaging.common.Message;
import io.eventuate.tram.messaging.common.MessageImpl;
import io.eventuate.tram.messaging.consumer.MessageHandler;
import io.lettuce.core.RedisCommandExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

public class ChannelProcessor {
  private Logger logger = LoggerFactory.getLogger(getClass());

  private String subscriptionIdentificationInfo;
  private CountDownLatch stopCountDownLatch = new CountDownLatch(1);
  private AtomicBoolean running = new AtomicBoolean(false);

  private TransactionTemplate transactionTemplate;
  private DuplicateMessageDetector duplicateMessageDetector;
  private String subscriberId;
  private String channel;
  private MessageHandler messageHandler;
  private RedisTemplate<String, String> redisTemplate;

  public ChannelProcessor(RedisTemplate<String, String> redisTemplate,
                          TransactionTemplate transactionTemplate,
                          DuplicateMessageDetector duplicateMessageDetector,
                          String subscriberId,
                          String channel,
                          MessageHandler messageHandler,
                          String subscriptionIdentificationInfo) {

    this.redisTemplate = redisTemplate;
    this.transactionTemplate = transactionTemplate;
    this.duplicateMessageDetector = duplicateMessageDetector;
    this.subscriberId = subscriberId;
    this.channel = channel;
    this.messageHandler = messageHandler;
    this.subscriptionIdentificationInfo = subscriptionIdentificationInfo;

    logger.info("channel processor is created (channel = {}, {})", channel, subscriptionIdentificationInfo);
  }

  public void process() {
    logger.info("channel processor started processing (channel = {}, {})", channel, subscriptionIdentificationInfo);
    running.set(true);
    makeSureConsumerGroupExists();
    processPendingRecords();
    processRegularRecords();
    stopCountDownLatch.countDown();
    logger.info("channel processor finished processing (channel = {}, {})", channel, subscriptionIdentificationInfo);
  }

  public void stop() {
    running.set(false);
    try {
      stopCountDownLatch.await();
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }

    logger.info("channel processor stopped processing (channel = {}, {})", channel, subscriptionIdentificationInfo);
  }

  private void makeSureConsumerGroupExists() {
    while (running.get()) {
      try {
        redisTemplate.opsForStream().createGroup(channel, ReadOffset.from("0"), subscriberId);
        return;
      } catch (RedisSystemException e) {
        if (isKeyDoesNotExist(e)) {
          logger.info("Stream {} does not exist!", channel);
          sleep();
          continue;
        } else if (isGroupExistsAlready(e)) {
          return;
        }
        throw e;
      }
    }
  }

  private boolean isKeyDoesNotExist(RedisSystemException e) {
    return isRedisCommandExceptionContainingMessage(e, "ERR The XGROUP subcommand requires the key to exist");
  }

  private boolean isGroupExistsAlready(RedisSystemException e) {
    return isRedisCommandExceptionContainingMessage(e, "Consumer Group name already exists");
  }

  private boolean isRedisCommandExceptionContainingMessage(RedisSystemException e, String expectedMessage) {
    String message = e.getCause().getMessage();

    return e.getCause() instanceof RedisCommandExecutionException &&
            message != null &&
            message.contains(expectedMessage);
  }

  private void processPendingRecords() {
    while (running.get()) {
      List<MapRecord<String, Object, Object>> pendingRecords = getPendingRecords();

      if (pendingRecords.isEmpty()) {
        return;
      }

      processRecords(pendingRecords);
    }
  }

  private void processRegularRecords() {
    while (running.get()) {
      processRecords(getUnprocessedRecords());
    }
  }

  private void processRecords(List<MapRecord<String, Object, Object>> records) {
    records.forEach(entries ->
            entries
                    .getValue()
                    .values()
                    .forEach(v -> processMessage(v.toString(), entries.getId())));
  }

  private void processMessage(String message, RecordId recordId) {

    logger.info("channel processor {} with channel {} got message: {}", subscriptionIdentificationInfo, channel, message);

    Message tramMessage = JSonMapper.fromJson(message, MessageImpl.class);

    transactionTemplate.execute(ts -> {
      if (!duplicateMessageDetector.isDuplicate(subscriberId, tramMessage.getId())) {
        try {
          messageHandler.accept(tramMessage);
        } catch (Throwable t) {
          logger.error(t.getMessage(), t);

          stopCountDownLatch.countDown();
          throw t;
        }
      }

      redisTemplate.opsForStream().acknowledge(channel, subscriberId, recordId);

      return null;
    });
  }

  private List<MapRecord<String, Object, Object>> getPendingRecords() {
    return getRecords(ReadOffset.from("0"));
  }

  private List<MapRecord<String, Object, Object>> getUnprocessedRecords() {
    return getRecords(ReadOffset.from(">"));
  }

  private List<MapRecord<String, Object, Object>> getRecords(ReadOffset readOffset) {

    return redisTemplate
            .opsForStream()
            .read(Consumer.from(subscriberId, subscriberId),
                    StreamReadOptions.empty().block(Duration.ofMillis(100)),
                    StreamOffset.create(channel, readOffset));
  }

  private void sleep() {
    try {
      Thread.sleep(500);
    } catch (Exception e) {
      logger.error(e.getMessage(), e);
      throw new RuntimeException(e);
    }
  }
}
