package jolie.net;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.ShutdownSignalException;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * Class for handling a connection.
 *
 * @author Claus Lindquist Henriksen (clih@itu.dk).
 * @author Michael Søby Andersen (msoa@itu.dk).
 * @author Alberto Garagnani (garagnanialberto@gmail.com)
 */
public final class AmqpConnection {
	private Connection conn;
	private Channel chan;

	private final URI location;
	private Map< String, String > locationParams;

	public AmqpConnection( URI location ) throws IOException {
		this.location = location;

		parseLocation();
		try {
			connect();
		} catch( URISyntaxException | NoSuchAlgorithmException | KeyManagementException | IOException
			| TimeoutException ex ) {
			throw new IOException( "Error during connect", ex );
		}
	}

	/**
	 * Connect to the location specified.
	 * 
	 * @throws URISyntaxException
	 * @throws NoSuchAlgorithmException
	 * @throws KeyManagementException
	 * @throws IOException
	 */
	public void connect()
		throws URISyntaxException, NoSuchAlgorithmException, KeyManagementException, IOException, TimeoutException {
		// Connect to the AMQP server.
		String schemeAndPath = location.toString().split( "\\?" )[ 0 ];
		ConnectionFactory factory = new ConnectionFactory();
		factory.setUri( schemeAndPath );
		conn = factory.newConnection();

		// Create the channel.
		chan = conn.createChannel();
	}

	/**
	 * Reopen the channel. Some exceptions close the channel, and we need to be able to reopen it.
	 * 
	 * @throws IOException
	 */
	public void reopenChannel() throws IOException {
		chan = conn.createChannel();
	}

	/**
	 * Parse the location to a key/value map.
	 */
	public void parseLocation() {
		locationParams = getQueryMap( location.getQuery() );
	}

	/**
	 * Get the channel associated with the connection.
	 * 
	 * @return The channel.
	 */
	public Channel getChannel() {
		return chan;
	}

	/**
	 * Get the map of location parameters.
	 * 
	 * @return The map of parameters.
	 */
	public Map< String, String > getLocationParams() {
		return locationParams;
	}

	/**
	 * Close the connection.
	 * 
	 * @throws IOException
	 */
	public void close() throws IOException {
		try {
			chan.close();
			conn.close();
		} catch( ShutdownSignalException | TimeoutException e ) {
		}
	}

	/**
	 * Parse a location to a map of keys to values.
	 * 
	 * @param query The query string to parse.
	 * @return The map of key/value pairs.
	 */
	public static Map< String, String > getQueryMap( String query ) {
		String[] params = query.split( "&" );
		Map< String, String > map = new HashMap();
		for( String param : params ) {
			String[] split = param.split( "=" );
			String name = split[ 0 ];
			String value = split.length >= 2 ? param.split( "=" )[ 1 ] : null;
			map.put( name, value );
		}

		// Following code allows to use default exchange ("") if exchange in location is specified like
		// 'exchange='
		if( map.containsKey( "exchange" ) ) {
			if( map.get( "exchange" ) == null )
				map.put( "exchange", "" );
		}
		return map;
	}
}
