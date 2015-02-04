package com.netflix.eureka2.channel;

import com.netflix.eureka2.metric.StateMachineMetrics;
import com.netflix.eureka2.transport.TransportClient;
import com.netflix.eureka2.transport.MessageConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rx.Observable;
import rx.functions.Action1;
import rx.functions.Func1;

/**
 * An abstract {@link ServiceChannel} implementation for common methods.
 *
 * @author Nitesh Kant
 */
public abstract class AbstractClientChannel<STATE extends Enum<STATE>> extends AbstractServiceChannel<STATE> {

    private static final Logger logger = LoggerFactory.getLogger(AbstractClientChannel.class);

    protected final TransportClient client;

    private volatile MessageConnection connectionIfConnected;

    private final Observable<MessageConnection> singleConnection;

    protected AbstractClientChannel(final STATE initState, final TransportClient client, StateMachineMetrics<STATE> metrics) {
        super(initState, metrics);
        this.client = client;

        singleConnection = client.connect()
                .take(1)
                .map(new Func1<MessageConnection, MessageConnection>() {
                    @Override
                    public MessageConnection call(MessageConnection serverConnection) {
                        if (connectionIfConnected == null) {
                            connectionIfConnected = serverConnection;
                        }
                        return connectionIfConnected;
                    }
                })
                .cache();
    }

    @Override
    protected void _close() {
        if (logger.isDebugEnabled()) {
            logger.debug("Closing client interest channel with state: " + state.get());
        }

        if (null != connectionIfConnected) {
            connectionIfConnected.shutdown();
        }
    }

    /**
     * Idempotent method that returns the one and only connection associated with this channel.
     *
     * @return The one and only connection associated with this channel.
     */
    protected Observable<MessageConnection> connect() {
        return singleConnection;
    }

    protected <T> Observable<Void> sendExpectAckOnConnection(MessageConnection connection, T message) {
        if (logger.isDebugEnabled()) {
            logger.debug("Sending message to the server: {}", message);
        }

        return subscribeToTransportSend(connection.submitWithAck(message), message.getClass().getSimpleName());
    }

    protected Observable<Void> sendErrorOnConnection(MessageConnection connection, Throwable throwable) {
        if (logger.isDebugEnabled()) {
            logger.debug("Sending error to the server: {}", throwable);
        }
        return subscribeToTransportSend(connection.onError(throwable), "error");
    }

    protected Observable<Void> sendAckOnConnection(MessageConnection connection) {
        if (logger.isDebugEnabled()) {
            logger.debug("Sending acknowledgment to the server");
        }
        return subscribeToTransportSend(connection.acknowledge(), "acknowledgment");
    }

    protected Observable<Void> subscribeToTransportSend(Observable<Void> sendResult, final String sendType) {
        sendResult.subscribe(new Action1<Void>() {
            @Override
            public void call(Void aVoid) {
                // Nothing to do for a void.
            }
        }, new Action1<Throwable>() {
            @Override
            public void call(Throwable throwable) {
                logger.warn("Failed to send " + sendType + " to the server. Closing the channel.", throwable);
                close(throwable);
            }
        });

        return sendResult;
    }
}