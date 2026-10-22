import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Formats MyBatis XML without letting a markup formatter split SQL operators or bind expressions.
 */
public class FormatMapperXml {
    public static void main(String[] args) throws Exception {
        boolean check = args[0].equals("--check");
        Path root = Path.of(args.length > 1 ? args[1] : "src/main/resources/mapper");
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        var changed = new ArrayList<Path>();
        try (var files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".xml")).sorted().toList()) {
                var document = factory.newDocumentBuilder().parse(file.toFile());
                var output =
                        new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                                .append(
                                        "<!DOCTYPE mapper PUBLIC \"-//mybatis.org//DTD Mapper"
                                                + " 3.0//EN\""
                                                + " \"https://mybatis.org/dtd/mybatis-3-mapper.dtd\">\n");
                render(document.getDocumentElement(), 0, output);
                if (!output.toString().equals(Files.readString(file))) {
                    changed.add(file);
                    if (!check) Files.writeString(file, output);
                }
            }
        }
        if (check && !changed.isEmpty()) {
            changed.forEach(System.err::println);
            System.exit(1);
        }
        System.out.println(
                "Mapper XML format " + (check ? "check passed" : "updated: " + changed.size()));
    }

    private static void render(Node node, int depth, StringBuilder out) {
        String indent = "    ".repeat(depth);
        if (node instanceof Element element) {
            out.append(indent).append('<').append(element.getTagName());
            var attributes = element.getAttributes();
            for (int i = 0; i < attributes.getLength(); i++) {
                var attr = attributes.item(i);
                out.append(' ')
                        .append(attr.getNodeName())
                        .append("=\"")
                        .append(escape(attr.getNodeValue()).replace("\"", "&quot;"))
                        .append('"');
            }
            if (!element.hasChildNodes()) {
                out.append("/>\n");
                return;
            }
            out.append(">\n");
            for (var child = element.getFirstChild(); child != null; child = child.getNextSibling())
                render(child, depth + 1, out);
            out.append(indent).append("</").append(element.getTagName()).append(">\n");
        } else if (node.getNodeType() == Node.COMMENT_NODE) {
            out.append(indent).append("<!--").append(node.getNodeValue().strip()).append("-->\n");
        } else if (node.getNodeType() == Node.TEXT_NODE
                || node.getNodeType() == Node.CDATA_SECTION_NODE) {
            for (String line : node.getNodeValue().split("\\R")) {
                String text = line.strip();
                if (!text.isEmpty()) appendSql(text, indent, out);
            }
        }
    }

    private static void appendSql(String text, String indent, StringBuilder out) {
        int width = Math.max(40, 100 - indent.length());
        while (text.length() > width) {
            char quote = 0;
            int lastSpace = -1;
            for (int i = 0; i <= width; i++) {
                char c = text.charAt(i);
                if (quote != 0) {
                    if (c == '\\') i++;
                    else if (c == quote) {
                        if (i + 1 < text.length() && text.charAt(i + 1) == quote) i++;
                        else quote = 0;
                    }
                } else if (c == '\'' || c == '"' || c == '`') quote = c;
                else if (Character.isWhitespace(c)) lastSpace = i;
            }
            if (lastSpace < 1) break;
            out.append(indent).append(escape(text.substring(0, lastSpace))).append('\n');
            text = text.substring(lastSpace).stripLeading();
        }
        out.append(indent).append(escape(text)).append('\n');
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
