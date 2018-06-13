package org.apache.nifi.processors.pravega;

import io.pravega.client.ClientConfig;
import io.pravega.client.stream.StreamConfiguration;
import org.apache.nifi.annotation.behavior.InputRequirement;
import org.apache.nifi.annotation.behavior.Stateful;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.SeeAlso;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnStopped;
import org.apache.nifi.annotation.lifecycle.OnUnscheduled;
import org.apache.nifi.components.AllowableValue;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.components.state.Scope;
import org.apache.nifi.logging.ComponentLog;
import org.apache.nifi.processor.*;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.util.StandardValidators;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Tags({"Pravega", "Nautilus", "Get", "Ingest", "Ingress", "Receive", "Consume", "Subscribe", "Stream"})
@CapabilityDescription("Consumes events from Pravega.")
@InputRequirement(InputRequirement.Requirement.INPUT_FORBIDDEN)
@Stateful(scopes = Scope.CLUSTER, description =
        "This processor stores the most recent successful checkpoint in the cluster state to allow it to resume when restarting the processor "
        + "or node. It also stores the Pravega reader group name to allow other readers to join it.")
@SeeAlso({PublishPravega.class})
public class ConsumePravega extends AbstractPravegaProcessor {

    static final AllowableValue STREAM_CUT_EARLIEST = new AllowableValue(
            "earliest",
            "earliest",
            "");
    static final AllowableValue STREAM_CUT_LATEST = new AllowableValue(
            "latest",
            "latest",
            "");

    static final PropertyDescriptor PROP_STREAM_CUT_METHOD = new PropertyDescriptor.Builder()
            .name("stream.cut.method")
            .displayName("Stream Cut")
            .description("If there is not a stream cut saved in the cluster state of this processor, then this specifies where to start.")
            .required(true)
            .allowableValues(STREAM_CUT_EARLIEST, STREAM_CUT_LATEST)
            .defaultValue(STREAM_CUT_LATEST.getValue())
            .build();

    static final PropertyDescriptor PROP_CHECKPOINT_PERIOD = new PropertyDescriptor.Builder()
            .name("checkpoint.period")
            .displayName("Checkpoint Period")
            .description("")
            .required(true)
            .defaultValue("1 secs")
            .addValidator(StandardValidators.TIME_PERIOD_VALIDATOR)
            .build();

    static final PropertyDescriptor PROP_CHECKPOINT_TIMEOUT = new PropertyDescriptor.Builder()
            .name("checkpoint.timeout")
            .displayName("Checkpoint Timeout")
            .description("")
            .required(true)
            .defaultValue("10 secs")
            .addValidator(StandardValidators.TIME_PERIOD_VALIDATOR)
            .build();

    static final PropertyDescriptor PROP_STOP_TIMEOUT = new PropertyDescriptor.Builder()
            .name("stop.timeout")
            .displayName("Stop Timeout")
            .description("")
            .required(true)
            .defaultValue("60 secs")
            .addValidator(StandardValidators.TIME_PERIOD_VALIDATOR)
            .build();

    static final PropertyDescriptor PROP_MINIMUM_PROCESSING_TIME = new PropertyDescriptor.Builder()
            .name("minimum.processing.time")
            .displayName("Minimum Processing Time")
            .description("")
            .required(true)
            .defaultValue("100 ms")
            .addValidator(StandardValidators.TIME_PERIOD_VALIDATOR)
            .build();

    static final Relationship REL_SUCCESS = new Relationship.Builder()
            .name("success")
            .description("FlowFiles received from Pravega.")
            .build();

    private volatile ConsumerPool consumerPool = null;

    static final List<PropertyDescriptor> DESCRIPTORS;
    static final Set<Relationship> RELATIONSHIPS;

    static {
        final List<PropertyDescriptor> descriptors = getAbstractPropertyDescriptors();
        descriptors.add(PROP_STREAM_CUT_METHOD);
        descriptors.add(PROP_CHECKPOINT_PERIOD);
        descriptors.add(PROP_CHECKPOINT_TIMEOUT);
        descriptors.add(PROP_STOP_TIMEOUT);
        descriptors.add(PROP_MINIMUM_PROCESSING_TIME);
        DESCRIPTORS = Collections.unmodifiableList(descriptors);
        RELATIONSHIPS = Collections.singleton(REL_SUCCESS);
    }

    @Override
    public Set<Relationship> getRelationships() {
        return RELATIONSHIPS;
    }

    @Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return DESCRIPTORS;
    }

    @Override
    protected void init(final ProcessorInitializationContext context) {
        super.init(context);
    }

    @OnUnscheduled
    public void onUnscheduled(final ProcessContext context) {
        System.out.println("ConsumePravega.onUnscheduled: BEGIN");
        // AbstractSessionFactoryProcessor.updateScheduledFalse is annotated with @OnUnscheduled but we are unsure
        // if it was already called. Therefore, we ensure that it is called here.
        updateScheduledFalse();
        ConsumerPool pool = consumerPool;
        if (pool != null) {
            pool.gracefulShutdown(context);
        }
        System.out.println("ConsumePravega.onUnscheduled: END");
    }

    @OnStopped
    public void close(final ProcessContext context) {
        logger.info("ConsumePravega.close: BEGIN");
        System.out.println("ConsumePravega.close: BEGIN");
        ConsumerPool pool;
        synchronized (this) {
            pool = consumerPool;
            consumerPool = null;
        }
        if (pool != null) {
            pool.close();
        }
        logger.info("ConsumePravega.close: END");
        System.out.println("ConsumePravega.close: END");
    }

    private synchronized ConsumerPool getConsumerPool(final ProcessContext context, final ProcessSessionFactory sessionFactory) throws Exception {
        ConsumerPool pool = consumerPool;
        if (pool != null) {
            return pool;
        }

        return consumerPool = createConsumerPool(context, sessionFactory, getLogger());
    }

    protected ConsumerPool createConsumerPool(final ProcessContext context, final ProcessSessionFactory sessionFactory, final ComponentLog log) throws Exception {
        final int maxConcurrentLeases = context.getMaxConcurrentTasks();
        final long checkpointPeriodMs = context.getProperty(PROP_CHECKPOINT_PERIOD).asTimePeriod(TimeUnit.MILLISECONDS);
        final long checkpointTimeoutMs = context.getProperty(PROP_CHECKPOINT_TIMEOUT).asTimePeriod(TimeUnit.MILLISECONDS);
        final long gracefulShutdownTimeoutMs = context.getProperty(PROP_STOP_TIMEOUT).asTimePeriod(TimeUnit.MILLISECONDS);
        final long minimumProcessingTimeMs = context.getProperty(PROP_MINIMUM_PROCESSING_TIME).asTimePeriod(TimeUnit.MILLISECONDS);
        final ClientConfig clientConfig = getClientConfig(context);
        final String scope = context.getProperty(PROP_SCOPE).getValue();
        final String streamName = context.getProperty(PROP_STREAM).getValue();
        final StreamConfiguration streamConfig = getStreamConfiguration(context);
        final String streamCutMethod = context.getProperty(PROP_STREAM_CUT_METHOD).getValue();
        List<String> streamNames = new ArrayList<>();
        streamNames.add(streamName);
        return new ConsumerPool(
                log,
                context.getStateManager(),
                sessionFactory,
                this::isPrimaryNode,
                maxConcurrentLeases,
                checkpointPeriodMs,
                checkpointTimeoutMs,
                gracefulShutdownTimeoutMs,
                minimumProcessingTimeMs,
                clientConfig,
                scope,
                streamNames,
                streamConfig,
                streamCutMethod,
                null,
                null);
    }

    @Override
    public void onTrigger(final ProcessContext context, final ProcessSessionFactory sessionFactory, final ProcessSession session) throws ProcessException {
        logger.debug("onTrigger: BEGIN");
        System.out.println("onTrigger: BEGIN");
        try {
            final ConsumerPool pool = getConsumerPool(context, sessionFactory);
            System.out.println("onTrigger: Return from getConsumerPool");
            if (pool == null) {
                context.yield();
            } else {
                try (final ConsumerLease lease = pool.obtainConsumer(session, context)) {
                    System.out.println("onTrigger: Return from obtainConsumer");
                    if (lease == null) {
                        context.yield();
                    } else {
                        try {
                            if (this.isScheduled()) {
                                System.out.println("onTrigger: Calling readEvents");
                                if (!lease.readEvents()) {
                                    context.yield();
                                }
                                System.out.println("onTrigger: Return from readEvents");
                            }
                        } catch (final Throwable t) {
                            logger.error("Exception while processing data from Pravega so will close the lease {} due to {}",
                                    new Object[]{lease, t}, t);
                            context.yield();
                        }
                    }
                }
            }
        } catch (final ProcessorNotReadyException e) {
            // This is an expected exception that occurs during startup of a non-primary node.
            logger.info("onTrigger: ProcessorNotReadyException", new Object[]{e});
            context.yield();
        } catch (final Exception e) {
            logger.info("onTrigger: Exception", new Object[]{e});
            System.out.println("onTrigger: Exception: " + e.toString());
            throw new RuntimeException(e);
        }
        logger.debug("onTrigger: END");
        System.out.println("onTrigger: END");
    }

}
