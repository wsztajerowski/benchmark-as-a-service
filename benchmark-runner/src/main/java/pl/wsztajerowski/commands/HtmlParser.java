package pl.wsztajerowski.commands;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import pl.wsztajerowski.JavaWonderlandException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static java.util.Optional.ofNullable;
import static java.util.function.Predicate.not;

public class HtmlParser {
    private final Element rootElement;

    HtmlParser(Element rootElement) {
        this.rootElement = rootElement;
    }

    public static HtmlParser getHtmlParser(Path filepath) {
        try {
            Document doc = Jsoup.parse(filepath.toFile(), "UTF-8");
            return new HtmlParser(doc);
        } catch (IOException e) {
            throw new JavaWonderlandException(e);
        }
    }

    public static HtmlParser getEmptyParser() {
        Document document = Jsoup.parse("<html></html>");
        return new HtmlParser(document);
    }

    public String getTextOfNextSibling(String selector) {
        return getFirstElementForSelector(selector)
            .map(Element::nextElementSibling)
            .map(Element::text)
            .orElse("");
    }

    public String getTextOfNextParentSibling(String selector) {
        return getFirstElementForSelector(selector)
            .map(Element::parent)
            .map(Element::nextElementSibling)
            .map(Element::text)
            .orElse("");
    }

    public String getTextOfParent(String selector) {
        return getFirstElementForSelector(selector)
            .map(Element::parent)
            .map(Element::text)
            .orElse("");
    }

    public List<String> getListOfAttributeValuesForSelector(String selector, String attributeName) {
        return rootElement
            .select(selector)
            .stream()
            .map(tag -> tag.attr(attributeName))
            .filter(not(
                String::isBlank))
            .toList();
    }

    public List<String> getListValuesForSelector(String selector) {
        return rootElement
            .select(selector)
            .stream()
            .map(Element::text)
            .filter(not(
                String::isBlank))
            .toList();
    }

    public Optional<HtmlParser> getParserForSelector(String selector) {
        return getFirstElementForSelector(selector)
            .map(HtmlParser::new);
    }

    private Optional<Element> getFirstElementForSelector(String selector) {
        return ofNullable(rootElement
            .select(selector)
            .first()
        );
    }
}
