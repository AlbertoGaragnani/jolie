package jolie.net;

import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

/**
 * Handles all AMQP connections.
 * 
 * @author Claus Lindquist Henriksen (clih@itu.dk).
 * @author Michael Søby Andersen (msoa@itu.dk).
 * @author Alberto Garagnani (garagnanialberto@gmail.com)
 */
public class AmqpConnectionHandler {
	private static final Map< URI, AmqpConnection > CONNECTIONS = new HashMap();

	/**
	 * Get a connection. If it does not exist, use setConnection to create it.
	 * 
	 * @param location The URI of the connection.
	 * @return The AmqpConnection
	 * @throws IOException
	 */
	public static AmqpConnection getConnection( URI location ) throws IOException {
		// If not already connected, do this.
		if( !CONNECTIONS.containsKey( location ) ) {
			setConnection( location );
		}

		return CONNECTIONS.get( location );
	}

	/**
	 * Create a connection, and set it.
	 * 
	 * @param location The URI of the connection.
	 * @throws IOException
	 */
	private static void setConnection( URI location ) throws IOException {
		CONNECTIONS.put( location, new AmqpConnection( location ) );
	}

	/**
	 * Close a connection.
	 * 
	 * @param location The URI of the connection to close.
	 * @throws IOException
	 */
	public static void closeConnection( URI location ) throws IOException {
		// If the .get returns null it means the connection has already been closed
		if( CONNECTIONS.get( location ) != null ) {
			CONNECTIONS.get( location ).close();
			CONNECTIONS.remove( location );
		}
	}
}
