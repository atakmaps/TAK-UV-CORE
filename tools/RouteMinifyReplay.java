import com.atakmap.coremap.cot.event.CotDetail;
import com.atakmap.coremap.cot.event.CotEvent;
import com.uvpro.plugin.cot.CotBuilder;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/**
 * Replay of {@link CotBuilder#minifyCotXml} for route waypoints.
 * Not a Gradle test — the plugin has no unit-test harness.
 * Run against compileCivDebugJavaWithJavac output plus the ATAK main.jar.
 */
public final class RouteMinifyReplay {

    private static final int MAX_FRAGMENT_PAYLOAD = 240;
    private static final int FRAGMENT_HEADER = 4;
    private static final int MAX_COT_COMPRESSED = 4096;

    public static void main(String[] args) throws Exception {
        String route3 = routeXml("route-3", 3);
        String route12 = routeXml("route-12", 12);
        String marker = markerXml();
        String shape = shapeXml();

        String min3 = CotBuilder.minifyCotXml(CotEvent.parse(route3));
        String min12 = CotBuilder.minifyCotXml(CotEvent.parse(route12));
        String minMarker = CotBuilder.minifyCotXml(CotEvent.parse(marker));
        String minShape = CotBuilder.minifyCotXml(CotEvent.parse(shape));

        assertLinks(min3, 3, "3-waypoint route");
        assertLinks(min12, 12, "12-waypoint route");
        assertLinkCount(minMarker, 0, "marker a-u-G");
        assertLinkCount(minShape, 4, "shape u-d-r");

        report("3-waypoint b-m-r", route3, min3);
        report("12-waypoint b-m-r", route12, min12);
        report("marker a-u-G (link must drop)", marker, minMarker);
        report("shape u-d-r (links must stay)", shape, minShape);
        System.out.println("REPLAY_OK");
    }

    private static void assertLinks(String xml, int expect, String label) {
        assertLinkCount(xml, expect, label);
        CotEvent event = CotEvent.parse(xml);
        List<CotDetail> links = event.getDetail().getChildrenByName("link");
        for (int i = 0; i < links.size(); i++) {
            CotDetail link = links.get(i);
            String point = link.getAttribute("point");
            String type = link.getAttribute("type");
            String callsign = link.getAttribute("callsign");
            if (point == null || !point.contains(",")) {
                fail(label + " link " + i + " missing point: " + point);
            }
            if (type == null || !type.startsWith("b-m-p-")) {
                fail(label + " link " + i + " missing type: " + type);
            }
            if (callsign == null || callsign.isEmpty()) {
                fail(label + " link " + i + " missing callsign (waypoint name)");
            }
        }
        if (xml.contains("<precisionlocation") || xml.contains("<creator")
                || xml.contains("<archive")) {
            fail(label + " still has a dropped detail");
        }
    }

    private static void assertLinkCount(String xml, int expect, String label) {
        CotEvent event = CotEvent.parse(xml);
        int n = event.getDetail().getChildrenByName("link").size();
        if (n != expect) {
            fail(label + " link count " + n + " expected " + expect + "\n" + xml);
        }
    }

    private static void report(String label, String original, String minified) throws Exception {
        byte[] full = gzip(original);
        byte[] min = gzip(minified);
        byte[] used = min.length < full.length ? min : full;
        int frags = fragments(used.length);
        System.out.println(label);
        System.out.println("  xmlBytes full=" + original.length() + " min=" + minified.length());
        System.out.println("  gzip full=" + full.length + " min=" + min.length + " used=" + used.length);
        System.out.println("  fragments=" + frags
                + (used.length <= MAX_FRAGMENT_PAYLOAD ? " (single packet)" : " (236-byte pieces)")
                + (used.length > MAX_COT_COMPRESSED ? " OVER_4096" : " under_4096"));
    }

    private static int fragments(int len) {
        if (len <= MAX_FRAGMENT_PAYLOAD) {
            return 1;
        }
        int per = MAX_FRAGMENT_PAYLOAD - FRAGMENT_HEADER;
        return (len + per - 1) / per;
    }

    private static byte[] gzip(String xml) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        GZIPOutputStream gzip = new GZIPOutputStream(baos);
        gzip.write(xml.getBytes("UTF-8"));
        gzip.close();
        return baos.toByteArray();
    }

    private static String routeXml(String uid, int waypoints) {
        StringBuilder links = new StringBuilder();
        for (int i = 0; i < waypoints; i++) {
            double lat = 29.69908 + (i * 0.00045);
            double lon = -91.00567 + (i * 0.00040);
            String type = (i == waypoints - 1) ? "b-m-p-c" : "b-m-p-w";
            String name = (i == 0) ? "Route 1 SP" : (i == waypoints - 1 ? "VDO" : ("CP " + i));
            links.append("<link uid=\"").append(uid).append("-wp").append(i)
                    .append("\" type=\"").append(type)
                    .append("\" point=\"").append(lat).append(",").append(lon)
                    .append("\" callsign=\"").append(name)
                    .append("\" remarks=\"\" relation=\"c\"/>");
        }
        return event(uid, "b-m-r",
                "<contact callsign=\"Route 1\"/>"
                        + "<link_attr color=\"-1\" type=\"Primary\" method=\"Driving\" stroke=\"3\"/>"
                        + links
                        + "<precisionlocation altsrc=\"GPS\"/>"
                        + "<archive/>"
                        + "<creator uid=\"ANDROID-cbc18e89b3b90d2d\" callsign=\"DEV-TWO\" type=\"a-f-G-U-C\"/>"
                        + "<status battery=\"40\"/>");
    }

    private static String markerXml() {
        return event("marker-1", "a-u-G",
                "<contact callsign=\"U.TEST\"/>"
                        + "<link uid=\"other\" type=\"a-f-G\" point=\"29.69908,-91.00567\" callsign=\"nope\" relation=\"p-p\"/>"
                        + "<precisionlocation altsrc=\"GPS\"/>"
                        + "<archive/>");
    }

    private static String shapeXml() {
        return event("shape-1", "u-d-r",
                "<contact callsign=\"Shape 1\"/>"
                        + "<link point=\"29.699,-91.006\"/>"
                        + "<link point=\"29.700,-91.006\"/>"
                        + "<link point=\"29.700,-91.005\"/>"
                        + "<link point=\"29.699,-91.005\"/>"
                        + "<archive/>");
    }

    private static String event(String uid, String type, String detail) {
        return "<event version=\"2.0\" uid=\"" + uid + "\" type=\"" + type
                + "\" how=\"h-e\" time=\"2026-10-23T12:00:00.000Z\""
                + " start=\"2026-10-23T12:00:00.000Z\" stale=\"2026-10-23T18:00:00.000Z\">"
                + "<point lat=\"29.69908\" lon=\"-91.00567\" hae=\"4\" ce=\"9999999\" le=\"9999999\"/>"
                + "<detail>" + detail + "</detail></event>";
    }

    private static void fail(String message) {
        System.err.println("REPLAY_FAIL " + message);
        System.exit(1);
    }

    private RouteMinifyReplay() {
    }
}
