import java.nio.file.*;
import javax.xml.parsers.*;
import javax.xml.XMLConstants;
import org.w3c.dom.*;
/** JDK XML reader, no network, DTD, XInclude or external entity resolution. */
class Xml {
  static String quote(String s) {
    StringBuilder b=new StringBuilder("\"");
    for(char c:s.toCharArray()) { switch(c) {
      case '"': b.append("\\\"");break; case '\\':b.append("\\\\");break;
      case '\n':b.append("\\n");break; case '\r':b.append("\\r");break;
      case '\t':b.append("\\t");break;
      default: if(c<32)b.append(String.format("\\u%04x",(int)c));else b.append(c);
    }}return b.append('"').toString();
  }
  static String json(Element e) {
    StringBuilder b=new StringBuilder("{\"name\":"+quote(e.getTagName())+",\"attrs\":{");
    NamedNodeMap a=e.getAttributes();for(int i=0;i<a.getLength();i++) {
      if(i>0)b.append(',');b.append(quote(a.item(i).getNodeName())).append(':').append(quote(a.item(i).getNodeValue()));
    }
    b.append("},\"children\":[");boolean first=true;
    for(Node n=e.getFirstChild();n!=null;n=n.getNextSibling()) if(n instanceof Element) {
      if(!first)b.append(',');first=false;b.append(json((Element)n));
    }
    return b.append("],\"text\":").append(quote(e.getTextContent())).append('}').toString();
  }
  public static void main(String[] args)throws Exception {
    var f=DocumentBuilderFactory.newInstance();f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);
    f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
    f.setFeature("http://xml.org/sax/features/external-general-entities",false);
    f.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
    f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
    f.setXIncludeAware(false);f.setExpandEntityReferences(false);
    System.out.println(json(f.newDocumentBuilder().parse(Path.of(args[0]).toFile()).getDocumentElement()));
  }
}
