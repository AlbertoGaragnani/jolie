package jolie.net;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import java.io.IOException;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import jolie.Interpreter;
import jolie.net.ext.CommProtocolFactory;
import jolie.net.ports.InputPort;
import jolie.net.protocols.CommProtocol;

/**
 * The CommListener for the Amqp implementation.
 *
 * @author Claus Lindquist Henriksen (clih@itu.dk).
 * @author Michael Søby Andersen (msoa@itu.dk).
 * @author Alberto Garagnani (garagnanialberto@gmail.com)
 */
public class AmqpCommListener extends CommListener {

	private final String queue;
	private final String consumerTag;
	private final AmqpCommChannel amqpCommChannel;

	/**
	 * @param interpreter The interpreter.
	 * @param protocolFactory The factory for the protocol.
	 * @param inputPort The InputPort this listener is created for.
	 * @throws IOException
	 */
	public AmqpCommListener( Interpreter interpreter, CommProtocolFactory protocolFactory, final InputPort inputPort )
		throws IOException {
		super( interpreter, protocolFactory, inputPort );

		queue = locationParams().get( "queue" );
		consumerTag =
			locationParams().get( "consumerTag" ) != null ? locationParams().get( "consumerTag" ) : "Jolie consumer";

		// Make sure that queue exists. If not create it as an auto-delete queue.
		try {
			// Try to declare queue, it will throw IOException if it exists, and close the channel.
			channel().queueDeclare( queue, false, false, true, null );
		} catch( IOException e ) {
			// Reopen channel.
			connection().reopenChannel();
		}

		final CommProtocol protocol = createProtocol();

		// Create channel for receive implementation.
		amqpCommChannel = new AmqpCommChannel( inputPort.location(), protocol );
		amqpCommChannel.setParentInputPort( inputPort );

	}

	@Override
	public void run() {
		try {
			// Consume from queue.
			channel().basicConsume( queue, false, consumerTag,
				new DefaultConsumer( channel() ) {
					@Override
					public void handleDelivery( String consumerTag, Envelope envelope, AMQP.BasicProperties properties,
						byte[] body ) throws IOException {

						AmqpMessage message = new AmqpMessage( envelope, body, properties );

						// Add the message in the ArrayDeque
						amqpCommChannel.addDataToProcess( message );

						// Receive.
						interpreter().commCore().scheduleReceive( amqpCommChannel, inputPort() );
					}
				} );
		} catch( IOException e ) {
			throw new RuntimeException( e );
		}
	}

	/**
	 * For shutting down the InputPort and listener.
	 */
	@Override
	public void shutdown() {
		try {
			// Close current connection.
			AmqpConnectionHandler.closeConnection( inputPort().location() );
		} catch( IOException ex ) {
			Logger.getLogger( AmqpCommListener.class.getName() ).log( Level.WARNING, null, ex );
		}
	}

	/**
	 * Get the channel for the Amqp connection.
	 *
	 * @return The channel for the connection.
	 * @throws IOException
	 */
	public final Channel channel() throws IOException {
		return connection().getChannel();
	}

	/**
	 * Get the location parameters for the connection.
	 *
	 * @return A map of the location parameters.
	 * @throws IOException
	 */
	public final Map< String, String > locationParams() throws IOException {
		return connection().getLocationParams();
	}

	/**
	 * Get the connection
	 *
	 * @return The AmqpConnection object.
	 * @throws IOException
	 */
	public final AmqpConnection connection() throws IOException {
		return AmqpConnectionHandler.getConnection( inputPort().location() );
	}

}
