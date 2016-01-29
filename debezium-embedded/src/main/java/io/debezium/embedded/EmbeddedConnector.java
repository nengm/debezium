/*
 * Copyright Debezium Authors.
 * 
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.embedded;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.apache.kafka.connect.connector.ConnectorContext;
import org.apache.kafka.connect.connector.Task;
import org.apache.kafka.connect.json.JsonConverter;
import org.apache.kafka.connect.source.SourceConnector;
import org.apache.kafka.connect.source.SourceRecord;
import org.apache.kafka.connect.source.SourceTask;
import org.apache.kafka.connect.source.SourceTaskContext;
import org.apache.kafka.connect.storage.Converter;
import org.apache.kafka.connect.storage.MemoryOffsetBackingStore;
import org.apache.kafka.connect.storage.OffsetBackingStore;
import org.apache.kafka.connect.storage.OffsetStorageReader;
import org.apache.kafka.connect.storage.OffsetStorageReaderImpl;
import org.apache.kafka.connect.storage.OffsetStorageWriter;
import org.apache.kafka.connect.storage.StringConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.debezium.annotation.ThreadSafe;
import io.debezium.config.Configuration;
import io.debezium.config.Field;
import io.debezium.util.Clock;
import io.debezium.util.VariableLatch;

/**
 * A mechanism for running a single Kafka Connect {@link SourceConnector} within an application's process. An embedded connector
 * is entirely standalone and only talks with the source system; no Kafka, Kafka Connect, or Zookeeper processes are needed.
 * Applications using an embedded connector simply set one up and supply a {@link Consumer consumer function} to which the
 * connector will pass all {@link SourceRecord}s containing database change events.
 * <p>
 * With an embedded connector, the application that runs the connector assumes all responsibility for fault tolerance,
 * scalability, and durability. Additionally, applications must specify how the connector can store its relational database
 * schema history and offsets. By default, this information will be stored in memory and will thus be lost upon application
 * restart.
 * <p>
 * Embedded connectors are designed to be submitted to an {@link Executor} or {@link ExecutorService} for execution by a single
 * thread, and a running connector can be stopped either by calling {@link #stop()} from another thread or by interrupting
 * the running thread (e.g., as is the case with {@link ExecutorService#shutdownNow()}).
 * 
 * @author Randall Hauch
 */
@ThreadSafe
public final class EmbeddedConnector implements Runnable {

    /**
     * A required field for an embedded connector that specifies the unique name for the connector instance.
     */
    @SuppressWarnings("unchecked")
    public static final Field CONNECTOR_NAME = Field.create("name")
                                                    .withDescription("Unique name for this connector instance.")
                                                    .withValidation(Field::isRequired);

    /**
     * A required field for an embedded connector that specifies the name of the normal Debezium connector's Java class.
     */
    @SuppressWarnings("unchecked")
    public static final Field CONNECTOR_CLASS = Field.create("connector.class")
                                                     .withDescription("The Java class for the connector")
                                                     .withValidation(Field::isRequired);

    /**
     * An optional advanced field that specifies the maximum amount of time that the embedded connector should wait
     * for an offset commit to complete.
     */
    @SuppressWarnings("unchecked")
    public static final Field OFFSET_COMMIT_TIMEOUT_MS_CONFIG = Field.create("offset.flush.timeout.ms")
                                                                     .withDescription("Maximum number of milliseconds to wait for records to flush and partition offset data to be"
                                                                             + " committed to offset storage before cancelling the process and restoring the offset "
                                                                             + "data to be committed in a future attempt.")
                                                                     .withDefault(5000L)
                                                                     .withValidation(Field::isPositiveInteger);

    protected static final Field INTERNAL_KEY_CONVERTER_CLASS = Field.create("internal.key.converter")
                                                                     .withDescription("The Converter class that should be used to serialize and deserialize key data for offsets.")
                                                                     .withDefault(StringConverter.class.getName());

    protected static final Field INTERNAL_VALUE_CONVERTER_CLASS = Field.create("internal.value.converter")
                                                                       .withDescription("The Converter class that should be used to serialize and deserialize value data for offsets.")
                                                                       .withDefault(JsonConverter.class.getName());

    /**
     * The array of fields that are required by each connectors.
     */
    public static final Field[] CONNECTOR_FIELDS = { CONNECTOR_NAME, CONNECTOR_CLASS };

    /**
     * A builder to set up and create {@link EmbeddedConnector} instances.
     */
    public static interface Builder {

        /**
         * Call the specified function for every {@link SourceRecord data change event} read from the source database.
         * This method must be called with a non-null consumer.
         * 
         * @param consumer the consumer function
         * @return this builder object so methods can be chained together; never null
         */
        Builder notifying(Consumer<SourceRecord> consumer);

        /**
         * Use the specified {@link Configuration#validate(Field[], Consumer) valid} configuration for the connector. This method
         * must be called with a non-null configuration.
         * 
         * @param config the configuration
         * @return this builder object so methods can be chained together; never null
         */
        Builder using(Configuration config);

        /**
         * Use the specified {@link OffsetCommitPolicy} to determine when offsets should be written to offset storage.
         * <p>
         * Passing <code>null</code> or not calling this method results in the connector using all offsets
         * {@link OffsetCommitPolicy#always() always} being committed after each batch of records are received from the source
         * system and processed by the {@link #notifying(Consumer) consumer function}.
         * 
         * @param policy the policy for when to commit offsets to the offset store
         * @return this builder object so methods can be chained together; never null
         */
        Builder using(OffsetCommitPolicy policy);

        /**
         * Use the specified storage mechanism for tracking how much data change history in the source database the connector
         * has processed.
         * <p>
         * Passing <code>null</code> or not calling this method results in the connector storing offsets in-memory, which means
         * when the application stops it will lose all record of how far the connector has read from the source database. If the
         * application upon restart should resume reading the source database where it left off, then a durable store must be
         * supplied.
         * 
         * @param offsetStorage the store for recording connector offsets
         * @return this builder object so methods can be chained together; never null
         */
        Builder using(OffsetBackingStore offsetStorage);

        /**
         * Use the specified class loader to find all necessary classes. Passing <code>null</code> or not calling this method
         * results in the connector using this class's class loader.
         * 
         * @param classLoader the class loader
         * @return this builder object so methods can be chained together; never null
         */
        Builder using(ClassLoader classLoader);

        /**
         * Use the specified clock when needing to determine the current time. Passing <code>null</code> or not calling this
         * method results in the connector using the {@link Clock#system() system clock}.
         * 
         * @param clock the clock
         * @return this builder object so methods can be chained together; never null
         */
        Builder using(Clock clock);

        /**
         * Build a new connector with the information previously supplied to this builder.
         * 
         * @return the embedded connector; never null
         * @throws IllegalArgumentException if a {@link #using(Configuration) configuration} or {@link #notifying(Consumer)
         *             consumer function} were not supplied before this method is called
         */
        EmbeddedConnector build();
    }

    /**
     * Obtain a new {@link Builder} instance that can be used to construct runnable {@link EmbeddedConnector} instances.
     * 
     * @return the new builder; never null
     */
    public static Builder create() {
        return new Builder() {
            private Configuration config;
            private OffsetBackingStore offsetStore;
            private OffsetCommitPolicy offsetCommitPolicy;
            private Consumer<SourceRecord> consumer;
            private ClassLoader classLoader;
            private Clock clock;

            @Override
            public Builder using(Configuration config) {
                this.config = config;
                return this;
            }

            @Override
            public Builder using(OffsetBackingStore offsetStore) {
                this.offsetStore = offsetStore;
                return this;
            }

            @Override
            public Builder using(OffsetCommitPolicy policy) {
                this.offsetCommitPolicy = policy;
                return this;
            }

            @Override
            public Builder using(ClassLoader classLoader) {
                this.classLoader = classLoader;
                return this;
            }

            @Override
            public Builder using(Clock clock) {
                this.clock = clock;
                return this;
            }

            @Override
            public Builder notifying(Consumer<SourceRecord> consumer) {
                this.consumer = consumer;
                return this;
            }

            @Override
            public EmbeddedConnector build() {
                if (offsetStore == null) offsetStore = new MemoryOffsetBackingStore();
                if (offsetCommitPolicy == null) offsetCommitPolicy = OffsetCommitPolicy.always();
                if (classLoader == null) classLoader = getClass().getClassLoader();
                if (clock == null) clock = Clock.system();
                Objects.requireNonNull(config, "A connector configuration must be specified.");
                Objects.requireNonNull(consumer, "A connector consumer must be specified.");
                return new EmbeddedConnector(config, offsetStore, offsetCommitPolicy, classLoader, clock, consumer);
            }

        };
    }

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Configuration config;
    private final OffsetBackingStore offsetStore;
    private final OffsetCommitPolicy offsetCommitPolicy;
    private final Clock clock;
    private final ClassLoader classLoader;
    private final Consumer<SourceRecord> consumer;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final VariableLatch latch = new VariableLatch(0);
    private final Converter keyConverter;
    private final Converter valueConverter;
    private long recordsSinceLastCommit = 0;
    private long timeSinceLastCommitMillis = 0;

    private EmbeddedConnector(Configuration config, OffsetBackingStore offsetStore,
            OffsetCommitPolicy offsetCommitPolicy, ClassLoader classLoader, Clock clock, Consumer<SourceRecord> consumer) {
        this.config = config;
        this.offsetStore = offsetStore;
        this.offsetCommitPolicy = offsetCommitPolicy;
        this.consumer = consumer;
        this.classLoader = classLoader;
        this.clock = clock;
        assert this.config != null;
        assert this.offsetStore != null;
        assert this.offsetCommitPolicy != null;
        assert this.consumer != null;
        assert this.classLoader != null;
        assert this.clock != null;
        keyConverter = config.getInstance(INTERNAL_KEY_CONVERTER_CLASS, Converter.class, () -> this.classLoader);
        keyConverter.configure(config.subset(INTERNAL_KEY_CONVERTER_CLASS.name() + ".", true).asMap(), false);
        valueConverter = config.getInstance(INTERNAL_VALUE_CONVERTER_CLASS, Converter.class, () -> this.classLoader);
        Configuration valueConverterConfig = config;
        if (valueConverter instanceof JsonConverter) {
            // Make sure that the JSON converter is configured to NOT enable schemas ...
            valueConverterConfig = config.edit().with(INTERNAL_VALUE_CONVERTER_CLASS + ".schemas.enable", false).build();
        }
        valueConverter.configure(valueConverterConfig.subset(INTERNAL_VALUE_CONVERTER_CLASS.name() + ".", true).asMap(), false);
    }

    /**
     * Determine if this embedded connector is currently running.
     * 
     * @return {@code true} if running, or {@code false} otherwise
     */
    protected boolean isRunning() {
        return this.running.get();
    }

    /**
     * Run this embedded connector and deliver database changes to the registered {@link Consumer}.
     * <p>
     * First, the method checks to see if this instance is currently {@link #run() running}, and if so immediately returns.
     * <p>
     * If the configuration is valid, this method connects to the MySQL server and begins reading the server's transaction log.
     * All messages are delivered in batches to the {@link Consumer} registered with this embedded connector. The batch size,
     * polling
     * frequency, and other parameters are controlled via configuration settings. This continues until this connector is
     * {@link #stop() stopped}.
     * <p>
     * Note that there are two ways to stop a connector running on a thread: calling {@link #stop()} from another thread, or
     * interrupting the thread (e.g., via {@link ExecutorService#shutdownNow()}).
     */
    @Override
    public void run() {
        if (running.compareAndSet(false, true)) {
            // Only one thread can be in this part of the method at a time ...
            latch.countUp();
            try {
                if (config.validate(CONNECTOR_FIELDS, logger::error)) {
                    // Instantiate the connector ...
                    final String connectorName = config.getString(CONNECTOR_NAME);
                    final String connectorClassName = config.getString(CONNECTOR_CLASS);
                    SourceConnector connector = null;
                    try {
                        @SuppressWarnings("unchecked")
                        Class<? extends SourceConnector> connectorClass = (Class<SourceConnector>) classLoader.loadClass(connectorClassName);
                        connector = connectorClass.newInstance();
                    } catch (Throwable t) {
                        logger.error("Unable to instantiate connector class {}", connectorClassName, t);
                        return;
                    }

                    // Initialize the connector using a context that does NOT respond to requests to reconfigure tasks ...
                    ConnectorContext context = () -> {};
                    connector.initialize(context);
                    OffsetStorageWriter offsetWriter = new OffsetStorageWriter(offsetStore, connectorName,
                            keyConverter, valueConverter);
                    OffsetStorageReader offsetReader = new OffsetStorageReaderImpl(offsetStore, connectorName,
                            keyConverter, valueConverter);
                    long commitTimeoutMs = config.getLong(OFFSET_COMMIT_TIMEOUT_MS_CONFIG);

                    try {
                        // Start the connector with the given properties and get the task configurations ...
                        connector.start(config.asMap());
                        List<Map<String, String>> taskConfigs = connector.taskConfigs(1);
                        Class<? extends Task> taskClass = connector.taskClass();
                        SourceTask task = null;
                        try {
                            task = (SourceTask) taskClass.newInstance();
                        } catch (IllegalAccessException | InstantiationException t) {
                            logger.error("Unable to instantiate connector's task class {}", taskClass.getName(), t);
                            return;
                        }
                        try {
                            SourceTaskContext taskContext = () -> offsetReader;
                            task.initialize(taskContext);
                            task.start(taskConfigs.get(0));
                        } catch (Throwable t) {
                            logger.error("Unable to initialize and start connector's task class {} with config: {}",
                                         taskClass.getName(), taskConfigs.get(0), t);
                            return;
                        }

                        recordsSinceLastCommit = 0;
                        timeSinceLastCommitMillis = clock.currentTimeInMillis();
                        while (running.get()) {
                            try {
                                List<SourceRecord> changeRecords = task.poll(); // blocks until there are values ...
                                if (changeRecords != null && !changeRecords.isEmpty()) {

                                    // First write out the last partition to offset storage ...
                                    SourceRecord lastRecord = changeRecords.get(changeRecords.size() - 1);
                                    lastRecord.sourceOffset();
                                    offsetWriter.offset(lastRecord.sourcePartition(), lastRecord.sourceOffset());

                                    // Now forward the records to the connector's consumer ...
                                    for (SourceRecord record : changeRecords) {
                                        consumer.accept(record);
                                    }

                                    // Flush the offsets to storage if necessary ...
                                    recordsSinceLastCommit += changeRecords.size();
                                    maybeFlush(offsetWriter, commitTimeoutMs);
                                }
                            } catch (InterruptedException e) {
                                // This thread was interrupted, which signals that the thread should stop work ...
                                // but first try to commit the offsets ...
                                maybeFlush(offsetWriter, commitTimeoutMs);
                                // Then clear the interrupted status ...
                                Thread.interrupted();
                                return;
                            }
                        }
                    } catch (Throwable t) {
                        logger.error("Error while running  to instantiate connector class {}", connectorClassName, t);
                    } finally {
                        connector.stop();
                    }
                }
            } finally {
                latch.countDown();
                running.set(false);
            }
        }
    }

    /**
     * Determine if we should flush offsets to storage, and if so then attempt to flush offsets.
     * 
     * @param offsetWriter the offset storage writer; may not be null
     * @param commitTimeoutMs the timeout to wait for commit results
     */
    protected void maybeFlush(OffsetStorageWriter offsetWriter, long commitTimeoutMs) {
        // Determine if we need to commit to offset storage ...
        if (this.offsetCommitPolicy.performCommit(recordsSinceLastCommit, timeSinceLastCommitMillis,
                                                  TimeUnit.MILLISECONDS)) {

            long started = clock.currentTimeInMillis();
            long timeout = started + commitTimeoutMs;
            offsetWriter.beginFlush();
            Future<Void> flush = offsetWriter.doFlush(this::completedFlush);
            if (flush == null) return; // no offsets to commit ...

            // Wait until the offsets are flushed ...
            try {
                flush.get(Math.max(timeout - clock.currentTimeInMillis(), 0), TimeUnit.MILLISECONDS);
                recordsSinceLastCommit = 0;
                timeSinceLastCommitMillis = clock.currentTimeInMillis();
            } catch (InterruptedException e) {
                logger.warn("Flush of {} offsets interrupted, cancelling", this);
                offsetWriter.cancelFlush();
            } catch (ExecutionException e) {
                logger.error("Flush of {} offsets threw an unexpected exception: ", this, e);
                offsetWriter.cancelFlush();
            } catch (TimeoutException e) {
                logger.error("Timed out waiting to flush {} offsets to storage", this);
                offsetWriter.cancelFlush();
            }
        }
    }

    protected void completedFlush(Throwable error, Void result) {
        if (error != null) {
            logger.error("Failed to flush {} offsets to storage: ", this, error);
        } else {
            logger.trace("Finished flushing {} offsets to storage", this);
        }
    }

    /**
     * Stop the execution of this embedded connector. This method does not block until the connector is stopped; use
     * {@link #await(long, TimeUnit)} for this purpose.
     * 
     * @return {@code true} if the connector was {@link #run() running} and will eventually stop, or {@code false} if it was not
     *         running when this method is called
     * @see #await(long, TimeUnit)
     */
    public boolean stop() {
        return running.getAndSet(false);
    }

    /**
     * Wait for the connector to complete processing. If the processor is not running, this method returns immediately; however,
     * if the processor is {@link #stop() stopped} and restarted before this method is called, this method will return only
     * when it completes the second time.
     * 
     * @param timeout the maximum amount of time to wait before returning
     * @param unit the unit of time; may not be null
     * @return {@code true} if the connector completed within the timeout (or was not running), or {@code false} if it is still
     *         running when the timeout occurred
     * @throws InterruptedException if this thread is interrupted while waiting for the completion of the connector
     */
    public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
        return latch.await(timeout, unit);
    }

    @Override
    public String toString() {
        return "EmbeddedConnector{id=" + config.getString(CONNECTOR_NAME) + '}';
    }
}
