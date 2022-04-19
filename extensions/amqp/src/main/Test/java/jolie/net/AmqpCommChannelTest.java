package jolie.net;

import jolie.net.protocols.CommProtocol;
import junit.framework.TestCase;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;

public class AmqpCommChannelTest extends TestCase {

	public void testSendImpl() throws URISyntaxException {
		URI uri = new URI( "amqp://localhost:5672?exchange=&queue=test&routingkey=test" );
		//CommProtocol protocol = new SodepProtocol()
		//AmqpCommChannel channel = new AmqpCommChannel( uri, )
	}
}