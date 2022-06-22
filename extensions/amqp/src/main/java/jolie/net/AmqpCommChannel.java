package jolie.net;

import com.rabbitmq.client.Channel;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
//import java.util.ArrayList;
//import java.util.List;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import jolie.net.ports.OutputPort;
import jolie.net.protocols.CommProtocol;
import jolie.runtime.Value;
import jolie.net.ports.InputPort;

/**
 * Communication-channel for handling communications (send/receive) for Amqp-connections.
 * 
 * @author Claus Lindquist Henriksen (clih@itu.dk).
 * @author Michael Søby Andersen (msoa@itu.dk).
 * @author Alberto Garagnani (garagnanialberto@gmail.com)
 */
public final class AmqpCommChannel extends StreamingCommChannel {

	// General.

	private final Map< Long, Boolean > acks = new HashMap<>();
	private final URI location;

	// For use in InputPort only.
	private ArrayDeque< AmqpMessage > dataToProcess2 = new ArrayDeque<>();
	private AmqpMessage dataToProcess;

	// For use in OutputPort only.
	private CommMessage message = null;

	// From params.
	String exchName;
	String queueName;
	String routingKey;

	/**
	 * @param location The location of the channel.
	 * @param protocol The protocol the channel should use.
	 * @throws IOException
	 */
	public AmqpCommChannel( URI location, CommProtocol protocol ) throws IOException {
		super( location, protocol );
		this.location = location;

		// Get parameters from location-uri.
		exchName = locationParams().get( "exchange" );
		queueName = locationParams().get( "queue" );
		routingKey = locationParams().get( "routingkey" );
		routingKey = routingKey != null ? routingKey : "";

		if( exchName != null ) {
			// Declare the auto-delete exchange and bind it to the queue only if we're not using the default one
			if( !exchName.equals( "" ) ) {
				channel().exchangeDeclare( exchName, "direct", false, true, false, null );
				channel().queueBind( queueName, exchName, routingKey );
			}
		}
		setToBeClosed( false );
	}

	/**
	 * The channels implementation of receive. This is used both in input and output ports.
	 * 
	 * @return The CommMessage we have received.
	 * @throws IOException
	 */
	@Override
	protected CommMessage recvImpl() throws IOException {
		// Make protocol give us the bytes to send.
		ByteArrayOutputStream ostream = new ByteArrayOutputStream();
		CommMessage returnMessage;

		// If we have some data to process.
		// This would come from the AmqpListener class, and should only be if we are an InputPort.
		if( dataToProcess != null ) {
			returnMessage = protocol().recv( new ByteArrayInputStream( dataToProcess.body ), ostream );
			return returnMessage;
		}

		// Otherwise we just have a message, data is not present.
		// This would come from sendImpl below, and only if we are an OutputPort.
		if( message != null ) {

			// Check on acks' list
			if( this.acks.get( message.requestId() ) ) {
				returnMessage = CommMessage.createResponse( message, Value.UNDEFINED_VALUE );
				this.acks.remove( message.requestId() );
				message = null;
				return returnMessage;
			}
		}

		// If we end up here, something is wrong.
		// The two cases above are the only ones we handle.
		throw new IOException( "Wrong context for receive!" );
	}

	/**
	 * The channels implementation of send. This is used both in input and output ports.
	 * 
	 * @param message The CommMessage to send.
	 * @throws IOException
	 */
	@Override
	protected void sendImpl( CommMessage message ) throws IOException {
		// Make protocol give us the bytes to send.
		ByteArrayOutputStream ostream = new ByteArrayOutputStream();
		protocol().send( ostream, message, null );

		this.message = message;

		// If from OutputPort.
		if( parentPort() instanceof OutputPort ) {
			// We just publish normally.
			channel().basicPublish( exchName, routingKey, null, ostream.toByteArray() );

			// Save the requestId on acks' map
			acks.put( this.message.requestId(), true );
		}

		// If from InputPort. We assume that we have something to send back to caller.
		else if( parentPort() instanceof InputPort ) {
			// Acknowledge that message has been processed.
			acknowledge( dataToProcess.envelope.getDeliveryTag() );
			dataToProcess = null;
		}

		// If we end up here, parentPort is neither an InputPort or an OutputPort, something is wrong.
		else {
			throw new IOException( "Port is of unexpected type!" );
		}
	}

	/**
	 * The channels implementation of close.
	 * 
	 * @throws IOException
	 */
	@Override
	protected void closeImpl() throws IOException {
		AmqpConnectionHandler.closeConnection( location );
	}

	/**
	 * Set the message that should be processed by this channel. This is called from AmqpListener when
	 * it receives data.
	 * 
	 * @param message The message that should be processed by this channel.
	 */
	public void setDataToProcess( AmqpMessage message ) {
		this.dataToProcess = message;
	}

	public AmqpMessage getDataToProcess() {
		return this.dataToProcess;
	}

	/**
	 * Acknowledge a message.
	 * 
	 * @param deliveryTag The delivery-tag of the message.
	 * @throws IOException
	 */
	public void acknowledge( long deliveryTag ) throws IOException {
		// Don't know why 2nd parameter is false, and API is no help.
		channel().basicAck( deliveryTag, false );
	}

	/**
	 * Get the Amqp channel.
	 * 
	 * @return The channel of the Amqp connection.
	 * @throws IOException
	 */
	public Channel channel() throws IOException {
		return AmqpConnectionHandler.getConnection( location ).getChannel();
	}

	/**
	 * Get the location parameters for the location string.
	 * 
	 * @return A map of parameters for the location string.
	 * @throws IOException
	 */
	public Map< String, String > locationParams() throws IOException {
		return AmqpConnectionHandler.getConnection( location ).getLocationParams();
	}
}
