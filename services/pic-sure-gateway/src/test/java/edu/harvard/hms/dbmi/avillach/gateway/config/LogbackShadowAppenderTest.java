package edu.harvard.hms.dbmi.avillach.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * I3 (log-config test): the {@code picsure.shadow} logger must have a dedicated raw appender ({@code %msg%n}) with {@code additivity=false}
 * in EVERY Spring profile, so the bare {@code SHADOW_GW} JSONL record is the whole log line -- never wrapped in the aio {@code JsonEncoder}
 * envelope (which escaped the record inside {@code "message"} and made {@code RecordParser} choke). Verified by inspecting
 * {@code logback-spring.xml}: the logger is a DIRECT child of {@code <configuration>} (not nested inside any {@code <springProfile>}), so
 * it applies unconditionally to every profile, its {@code additivity} is {@code false}, and its appender's encoder pattern is
 * {@code %msg%n}.
 */
class LogbackShadowAppenderTest {

    @Test
    void picsureShadowLoggerIsRawAndNonAdditiveInEveryProfile() throws Exception {
        Document doc = loadLogbackConfig();
        Element configuration = doc.getDocumentElement();

        Element shadowLogger = findTopLevelShadowLogger(configuration);
        assertThat(shadowLogger).as("picsure.shadow logger defined as a top-level <logger> (applies to all profiles)").isNotNull();
        assertThat(shadowLogger.getAttribute("additivity")).as("additivity must be off so shadow lines never hit the envelope appender")
            .isEqualTo("false");

        String appenderRef = ((Element) shadowLogger.getElementsByTagName("appender-ref").item(0)).getAttribute("ref");
        Element appender = findTopLevelAppender(configuration, appenderRef);
        assertThat(appender).as("the referenced shadow appender is defined at top level").isNotNull();

        String pattern = appender.getElementsByTagName("pattern").item(0).getTextContent().trim();
        assertThat(pattern).as("bare record as the whole line -- no timestamp/level prefix, no JSON envelope").isEqualTo("%msg%n");
    }

    @Test
    void picsureShadowLoggerIsNotNestedInsideASingleProfile() throws Exception {
        // A logger nested inside one <springProfile> would leave additivity ON (envelope-wrapped) in the other profiles.
        Document doc = loadLogbackConfig();
        NodeList profiles = doc.getElementsByTagName("springProfile");
        for (int i = 0; i < profiles.getLength(); i++) {
            Element profile = (Element) profiles.item(i);
            NodeList loggers = profile.getElementsByTagName("logger");
            for (int j = 0; j < loggers.getLength(); j++) {
                assertThat(((Element) loggers.item(j)).getAttribute("name")).as("picsure.shadow must not be scoped to a single profile")
                    .isNotEqualTo("picsure.shadow");
            }
        }
    }

    private static Document loadLogbackConfig() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        try (InputStream in = LogbackShadowAppenderTest.class.getResourceAsStream("/logback-spring.xml")) {
            assertThat(in).as("logback-spring.xml on the classpath").isNotNull();
            return factory.newDocumentBuilder().parse(in);
        }
    }

    private static Element findTopLevelShadowLogger(Element configuration) {
        for (Element logger : directChildren(configuration, "logger")) {
            if ("picsure.shadow".equals(logger.getAttribute("name"))) {
                return logger;
            }
        }
        return null;
    }

    private static Element findTopLevelAppender(Element configuration, String name) {
        for (Element appender : directChildren(configuration, "appender")) {
            if (name.equals(appender.getAttribute("name"))) {
                return appender;
            }
        }
        return null;
    }

    private static java.util.List<Element> directChildren(Element parent, String tag) {
        java.util.List<Element> result = new java.util.ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && tag.equals(node.getNodeName())) {
                result.add((Element) node);
            }
        }
        return result;
    }
}
