/*
 *   Copyright (C) 2017 by Stefano Pio Zingaro <stefanopio.zingaro@unibo.it>  
 *   Copyright (C) 2017 by Saverio Giallorenzo <saverio.giallorenzo@gmail.com>
 *                                                                             
 *   This program is free software; you can redistribute it and/or modify      
 *   it under the terms of the GNU Library General Public License as           
 *   published by the Free Software Foundation; either version 2 of the        
 *   License, or (at your option) any later version.                           
 *                                                                             
 *   This program is distributed in the hope that it will be useful,           
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of            
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the             
 *   GNU General Public License for more details.                              
 *                                                                             
 *   You should have received a copy of the GNU Library General Public         
 *   License along with this program; if not, write to the                     
 *   Free Software Foundation, Inc.,                                           
 *   59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.                 
 *                                                                             
 *   For details about the authors of this software, see the AUTHORS file.     
 */
package jolie.net.coap;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageCodec;
import io.netty.util.CharsetUtil;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import jolie.Interpreter;
import jolie.js.JsUtils;

import jolie.net.CoapProtocol;
import jolie.net.CommCore;
import jolie.net.CommMessage;
import jolie.net.NioDatagramCommChannel;
import jolie.net.coap.message.CoapMessage;
import jolie.net.coap.message.MessageCode;
import jolie.net.coap.message.MessageType;
import jolie.runtime.ByteArray;

import jolie.runtime.FaultException;
import jolie.runtime.Value;
import jolie.runtime.typing.Type;
import jolie.runtime.typing.TypeCastingException;
import jolie.runtime.typing.TypeCheckingException;

import jolie.xml.XmlUtils;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

public class CoapCodecHandler
    extends MessageToMessageCodec<CoapMessage, CommMessage> {

  private static final Charset charset = CharsetUtil.UTF_8;
  public static final int GET = 1;
  public static final int POST = 2;
  public static final int PUT = 3;
  public static final int DELETE = 4;
  private static final Map<String, Integer> allowedMethods = new HashMap<>();

  static {
    allowedMethods.put("GET", GET);
    allowedMethods.put("POST", POST);
    allowedMethods.put("PUT", PUT);
    allowedMethods.put("DELETE", DELETE);
  }

  private final boolean input;
  private final CoapProtocol protocol;

  private Channel cc;
  private CommMessage commMessageRequest;
  private CommMessage commMessageResponse;

  public CoapCodecHandler(CoapProtocol prt) {
    this.protocol = prt;
    this.input = prt.isInput;
  }

  @Override
  public void channelActive(ChannelHandlerContext ctx) throws Exception {
    cc = ctx.channel();
    ((CommCore.ExecutionContextThread) Thread.currentThread())
        .executionThread(cc
            .attr(NioDatagramCommChannel.EXECUTION_CONTEXT).get());
  }

  @Override
  protected void encode(ChannelHandlerContext ctx,
      CommMessage in, List<Object> out) throws Exception {

    String operationName = in.operationName();
    int messageType = getMessageType(operationName);
    int messageCode = getMessageCode(operationName);
    CoapMessage msg = new CoapMessage(messageType, messageCode) {
      // override Coap Message methods code portion
    };
    ByteBuf payload = valueToByteBuf(in);
    msg.content(payload);
    out.add(msg);

    if (input) { // input port - RR

      this.commMessageResponse = in;

    } else { // output port - OW

      if (isOneWay(operationName)) {
        if (messageType == MessageType.NON) {
          sendAck(ctx, in);
        } else if (messageType == MessageType.CON) {
          this.commMessageRequest = in;
        }
      } else {
        this.commMessageRequest = in;
      }
    }
  }

  @Override
  protected void decode(ChannelHandlerContext ctx,
      CoapMessage in, List<Object> out) throws Exception {

    if (input) { // input port - OW and RR

      if (in.getMessageType() == MessageType.ACK) { // RR ack to comm core
        if (this.commMessageResponse != null) {
          sendAck(ctx, this.commMessageResponse);
        } else { // AH AH AH!
          Interpreter.getInstance().logSevere("No Comm Message "
              + "Waiting for Acknowledgement");
        }
      } else {

        if (in.getMessageType() == MessageType.CON) { // OW ack to client
          this.cc.writeAndFlush(CoapMessage
              .createEmptyAcknowledgement(in.getMessageID()));
        }

        String operationName = getOperationName(in);
        CommMessage msg = CommMessage.createRequest(operationName,
            "/", byteBufToValue(in, operationName));
        out.add(msg);
      }

    } else { // output port - OW and RR (ack V response)

      if (in.getMessageType() == MessageType.ACK) {
        if (this.commMessageRequest != null) {
          out.add(CommMessage
              .createEmptyResponse(commMessageRequest));
        } else { // should not be handled
          Interpreter.getInstance().logSevere("No Comm Message "
              + "Waiting for Acknowledgement");
        }
      } else if (in.isResponse()) { // maybe no need of this check ??
        if (in.getMessageType() == MessageType.ACK) {
          cc.writeAndFlush(CoapMessage
              .createEmptyAcknowledgement(in.getMessageID()));
        }

        out.add(CommMessage.createResponse(commMessageRequest,
            byteBufToValue(in,
                commMessageRequest.operationName())));
      }
    }
  }

  private ByteBuf valueToByteBuf(CommMessage in) throws Exception {

    ByteBuf bb = Unpooled.buffer();
    String format = format(in.operationName());
    String message;
    Value v = in.isFault() ? Value.create(in.fault().getMessage())
        : in.value();
    switch (format) {
      case "json":
        StringBuilder jsonStringBuilder = new StringBuilder();
        JsUtils.valueToJsonString(v, true, protocol.getSendType(
            in.operationName()), jsonStringBuilder);
        message = jsonStringBuilder.toString();
        break;
      case "xml":
        DocumentBuilder db = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder();
        Document doc = db.newDocument();
        Element root = doc.createElement(in.operationName());
        doc.appendChild(root);
        XmlUtils.valueToDocument(v, root, doc);
        Source src = new DOMSource(doc);
        ByteArrayOutputStream strm = new ByteArrayOutputStream();
        Result dest = new StreamResult(strm);
        Transformer trf = TransformerFactory.newInstance()
            .newTransformer();
        trf.setOutputProperty(OutputKeys.ENCODING, charset.name());
        trf.transform(src, dest);
        message = strm.toString();
        break;
      case "raw":
        message = valueToRaw(v);
        break;
      default:
        throw new FaultException("Format " + format + " not "
            + "supported for operation " + in.operationName());
    }

    bb.writeBytes(message.getBytes(charset));

    return bb;
  }

  private String format(String operationName) {
    return protocol.hasOperationSpecificParameter(operationName,
        Parameters.FORMAT)
            ? protocol.getOperationSpecificStringParameter(
                operationName,
                Parameters.FORMAT) : "raw";
  }

  private String valueToRaw(Value value) {
    Object valueObject = value.valueObject();
    String str = "";
    if (valueObject instanceof String) {
      str = ((String) valueObject);
    } else if (valueObject instanceof Integer) {
      str = ((Integer) valueObject).toString();
    } else if (valueObject instanceof Double) {
      str = ((Double) valueObject).toString();
    } else if (valueObject instanceof ByteArray) {
      str = ((ByteArray) valueObject).toString();
    } else if (valueObject instanceof Boolean) {
      str = ((Boolean) valueObject).toString();
    } else if (valueObject instanceof Long) {
      str = ((Long) valueObject).toString();
    }

    return str;
  }

  private void sendAck(ChannelHandlerContext ctx, CommMessage in) {
    ctx.pipeline().fireChannelRead(new CommMessage(
        in.id(), in.operationName(), "/", Value.create(), null));
  }

  private int getMessageType(String operationName) {
    if (protocol.hasOperationSpecificParameter(operationName,
        Parameters.CONFIRMABLE)) {
      if (protocol.getOperationSpecificParameterFirstValue(operationName,
          Parameters.CONFIRMABLE).boolValue()) {
        return MessageType.CON;
      }
    }
    return MessageType.NON;
  }

  private int getMessageCode(String operationName) {
    if (protocol.hasOperationSpecificParameter(operationName,
        Parameters.METHOD)) {
      String method = protocol
          .getOperationSpecificParameterFirstValue(operationName,
              Parameters.METHOD).strValue();
      if (allowedMethods.containsKey(method)) {
        return allowedMethods.get(method);
      } else {
        Interpreter.getInstance().logSevere("Methods allowed are: "
            + "POST, GET, PUT, DELETE.");
      }
    }
    return MessageCode.POST;
  }

  private Value byteBufToValue(CoapMessage in, String operationName)
      throws IOException,
      FaultException, ParserConfigurationException, SAXException,
      TypeCheckingException {

    if (protocol.checkBooleanParameter(Parameters.DEBUG)) {
      Interpreter.getInstance().logInfo("Received message: " + in);
    }

    ByteBuf content = Unpooled.wrappedBuffer(in.content());
    Value value = Value.create();
    Type type = protocol.getSendType(operationName);
    String format = format(operationName);
    String message = content.toString(charset);

    if (message.length() > 0) {
      switch (format) {
        case "xml":
          parseXml(content, value);
          break;
        case "json":
          parseJson(content, value);
          break;
        case "raw":
          parseRaw(message, value, type);
          break;
        default:
          throw new FaultException("Format " + format
              + "is not supported. Supported formats are: "
              + "xml, json and raw");
      }

      // for XML format
      try {
        value = type.cast(value);
      } catch (TypeCastingException e) {
        // do nothing
      }

    } else {

      value = Value.create();
      try {
        type.check(value);
      } catch (TypeCheckingException ex1) {
        value = Value.create("");
        try {
          type.check(value);
        } catch (TypeCheckingException ex2) {
          value = Value.create(new ByteArray(new byte[0]));
          try {
            type.check(value);
          } catch (TypeCheckingException ex3) {
            value = Value.create();
          }
        }
      }
    }

    return value;
  }

  private void parseXml(ByteBuf content, Value value)
      throws SAXException, ParserConfigurationException, IOException {

    DocumentBuilderFactory docBuilderFactory
        = DocumentBuilderFactory.newInstance();
    DocumentBuilder builder = docBuilderFactory.newDocumentBuilder();
    InputSource src = new InputSource(new ByteBufInputStream(content));
    src.setEncoding(charset.name());
    Document doc = builder.parse(src);
    XmlUtils.documentToValue(doc, value);
  }

  private void parseJson(ByteBuf content, Value value) throws IOException {
    JsUtils.parseJsonIntoValue(new InputStreamReader(
        new ByteBufInputStream(content)), value,
        protocol.checkStringParameter(Parameters.JSON_ENCODING,
            "strict"));
  }

  private void parseRaw(String message, Value value, Type type)
      throws TypeCheckingException {

    try {
      type.check(Value.create(message));
      value.setValue(message);
    } catch (TypeCheckingException e1) {
      if (isNumeric(message)) {
        try {
          if (message.equals("0")) {
            type.check(Value.create(false));
            value.setValue(false);
          } else {
            if (message.equals("1")) {
              type.check(Value.create(true));
              value.setValue(true);
            } else {
              throw new TypeCheckingException("");
            }
          }
        } catch (TypeCheckingException e) {
          try {
            value.setValue(Integer.parseInt(message));
          } catch (NumberFormatException nfe) {
            try {
              value.setValue(Long.parseLong(message));
            } catch (NumberFormatException nfe1) {
              try {
                value.setValue(Double.parseDouble(message));
              } catch (NumberFormatException nfe2) {
              }
            }
          }
        }
      } else {
        try {
          type.check(Value.create(new ByteArray(message.getBytes())));
          value.setValue(new ByteArray(message.getBytes()));
        } catch (TypeCheckingException e) {
          value.setValue(message);
        }
      }
    }
  }

  private boolean isNumeric(final CharSequence cs) {

    if (cs.length() == 0) {
      return false;
    }
    final int sz = cs.length();
    for (int i = 0; i < sz; i++) {
      if (!Character.isDigit(cs.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  /**
   * TODO it can be upgrade to Streaming Comm Channel method
   *
   * @param operationName
   * @return
   */
  public boolean isOneWay(String operationName) {
    return protocol.channel().parentPort().getInterface()
        .oneWayOperations().containsKey(operationName);
  }

  private String getOperationName(CoapMessage in) {
    throw new UnsupportedOperationException("Not supported yet.");
  }

  private static class Parameters {

    private static final String DEBUG = "debug";
    private static final String FORMAT = "format";
    private static final String CONFIRMABLE = "confirmable";
    private static final String METHOD = "method";
    private static final String JSON_ENCODING = "json_encoding";

  }
}
