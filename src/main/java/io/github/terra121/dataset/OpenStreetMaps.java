package io.github.terra121.dataset;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.terra121.TerraConfig;
import io.github.terra121.TerraMod;
import io.github.terra121.projection.GeographicProjection;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.MultiPolygon;

import de.topobyte.osm4j.core.access.OsmReader;
import de.topobyte.osm4j.core.dataset.InMemoryMapDataSet;
import de.topobyte.osm4j.core.dataset.MapDataSetLoader;
import de.topobyte.osm4j.core.model.iface.OsmRelation;
import de.topobyte.osm4j.core.model.iface.OsmWay;
import de.topobyte.osm4j.core.model.util.OsmModelUtil;
import de.topobyte.osm4j.core.resolve.EntityFinder;
import de.topobyte.osm4j.core.resolve.EntityFinders;
import de.topobyte.osm4j.core.resolve.EntityNotFoundException;
import de.topobyte.osm4j.core.resolve.EntityNotFoundStrategy;
import de.topobyte.osm4j.geometry.RegionBuilder;
import de.topobyte.osm4j.geometry.RegionBuilderResult;
import de.topobyte.osm4j.geometry.WayBuilder;
import de.topobyte.osm4j.geometry.WayBuilderResult;
import de.topobyte.osm4j.xml.dynsax.OsmXmlReader;



public class OpenStreetMaps {

    private static final double CHUNK_SIZE = 16;
    public static final double TILE_SIZE = 1 / 60.0;//250*(360.0/40075000.0);
    private static final double NOTHING = 0.01;

    private static String overpassInstance = TerraConfig.serverOverpassDefault;

    private static final String URL_PREFACE = "/?data=out;way(";
    private static final String URL_B = ")%20tags%20qt;(._<;);out%20body%20qt;";
    private static final String URL_C = "is_in(";
    private static Thread fallbackCancellerThread;

    public static String getOverpassEndpoint() {
        return overpassInstance;
    }

    public static void setOverpassEndpoint(String urlBase) {
        OpenStreetMaps.overpassInstance = urlBase;
    }

    public static void cancelFallbackThread() {
        if (fallbackCancellerThread != null && fallbackCancellerThread.isAlive()) {
            fallbackCancellerThread.interrupt();
        }
        fallbackCancellerThread = null;
    }
    private String URL_A = ")";
    private HashMap<Coord, Set<Edge>> chunks;
    public LinkedHashMap<Coord, Region> regions;
    public Water water;
    private int numcache = TerraConfig.osmCacheSize;
    private ArrayList<Edge> allEdges;
    private Gson gson;
    private GeographicProjection projection;
    Type wayType;
    byte wayLanes;
    boolean doRoad;
    boolean doWater;
    boolean doBuildings;

    private List<BuildingElem> buildings = new ArrayList<>();
    private List<WaterElem> waters = new ArrayList<>();
    private List<RoadElem> roads = new ArrayList<>();
    private InMemoryMapDataSet data;

    public OpenStreetMaps(GeographicProjection proj, boolean doRoad, boolean doWater, boolean doBuildings) {
        this.gson = new GsonBuilder().create();
        this.chunks = new LinkedHashMap<>();
        this.allEdges = new ArrayList<>();
        this.regions = new LinkedHashMap<>();
        this.projection = proj;
        try {
            this.water = new Water(this, 256);
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

        this.doRoad = doRoad;
        this.doWater = doWater;
        this.doBuildings = doBuildings;

        if (!doBuildings) {
            this.URL_A += "[!\"building\"]";
        }
        if (!doRoad) {
            this.URL_A += "[!\"highway\"]";
        }
        if (!doWater) {
            this.URL_A += "[!\"water\"][!\"natural\"][!\"waterway\"]";
        }
        this.URL_A += ";out%20geom(";
    }

    private WayBuilder wayBuilder = new WayBuilder();
    private RegionBuilder regionBuilder = new RegionBuilder();

    private Collection<LineString> getLine(OsmWay way)
    {
        List<LineString> results = new ArrayList<>();
        try {
            WayBuilderResult lines = wayBuilder.build(way, data);
            results.addAll(lines.getLineStrings());
            if (lines.getLinearRing() != null) {
                results.add(lines.getLinearRing());
            }
        } catch (EntityNotFoundException e) {
            // ignore
        }
        return results;
    }

    private MultiPolygon getPolygon(OsmWay way)
    {
        try {
            RegionBuilderResult region = regionBuilder.build(way, data);
            return region.getMultiPolygon();
        } catch (EntityNotFoundException e) {
            return null;
        }
    }

    private MultiPolygon getPolygon(OsmRelation relation)
    {
        try {
            RegionBuilderResult region = regionBuilder.build(relation, data);
            return region.getMultiPolygon();
        } catch (EntityNotFoundException e) {
            return null;
        }
    }

    private Type typeCheck(Map<String, String> tags){
        for (String tag : tags.keySet()) {
            switch (tag) {
                case "building": //building areas
                    if (this.doBuildings)
                        return Type.BUILDING;
                    break;
                case "natural": //water areas
                case "waterway": //TODO: remove type natural and replace with land polygons
                case "water":
                    if (this.doWater)
                        return Type.STREAM; //TODO: refine water code after testing
                    break;
                case "tunnel":
                case "bridge": //skip if tunnel or bridge
                    break;
                case "highway": //street areas
                case "railway":
                    if(this.doRoad)
                        return Type.ROAD;
                    break;
                }
            }
            return Type.IGNORE;
    }


/*
    private boolean pointWithinPolygons(List<Geometry> array, double lon, double lat){
        //compute position within polygons of array
        for (Geometry polygon : array){
            if(pointWithinBounds(polygon, new Coordinate(lon, lat))) return true;
        }
        return false;
    }
*/
    public Coord getRegion(double lon, double lat) {
        return new Coord((int) Math.floor(lon / TILE_SIZE), (int) Math.floor(lat / TILE_SIZE));
    }

    public Set<Edge> chunkStructures(int x, int z) {
        Coord coord = new Coord(x, z);

        if (this.regionCache(this.projection.toGeo(x * CHUNK_SIZE, z * CHUNK_SIZE)) == null) {
            return null;
        }

        if (this.regionCache(this.projection.toGeo((x + 1) * CHUNK_SIZE, z * CHUNK_SIZE)) == null) {
            return null;
        }

        if (this.regionCache(this.projection.toGeo((x + 1) * CHUNK_SIZE, (z + 1) * CHUNK_SIZE)) == null) {
            return null;
        }

        if (this.regionCache(this.projection.toGeo(x * CHUNK_SIZE, (z + 1) * CHUNK_SIZE)) == null) {
            return null;
        }

        return this.chunks.get(coord);
    }

    public Region regionCache(double[] corner) {

        //bound check
        if (!(corner[0] >= -180 && corner[0] <= 180 && corner[1] >= -80 && corner[1] <= 80)) {
            return null;
        }

        Coord coord = this.getRegion(corner[0], corner[1]);
        Region region;

        if ((region = this.regions.get(coord)) == null) {
            region = new Region(coord, this.water);
            int i;
            for (i = 0; i < 5 && !this.regiondownload(region); i++) {
            }
            this.regions.put(coord, region);
            if (this.regions.size() > this.numcache) {
                //TODO: delete beter
                Iterator<Region> it = this.regions.values().iterator();
                Region delete = it.next();
                it.remove();
                this.removeRegion(delete);
            }

            if (i == 5) {
                region.failedDownload = true;
                TerraMod.LOGGER.error("OSM region" + region.coord.x + ' ' + region.coord.y + " failed to download several times, no structures will spawn");
                return null;
            }
        } else if (region.failedDownload) {
            return null; //don't return dummy regions
        }
        return region;
    }

    public boolean regiondownload(Region region) {
        double X = region.coord.x * TILE_SIZE;
        double Y = region.coord.y * TILE_SIZE;

        //limit extreme (a.k.a. way too clustered on some projections) requests and out of bounds requests
        if (Y > 80 || Y < -80 || X < -180 || X > 180 - TILE_SIZE) {
            region.failedDownload = true;
            return false;
        }


        try {
            String bottomleft = Y + "," + X;
            String bbox = bottomleft + ',' + (Y + TILE_SIZE) + ',' + (X + TILE_SIZE);

            String urltext = overpassInstance + URL_PREFACE + bbox + this.URL_A + bbox + URL_B;
            if (this.doWater) {
                String URL_SUFFIX = ");area._[~\"natural|waterway\"~\"water|riverbank\"];out%20ids;";
                urltext += URL_C + bottomleft + URL_SUFFIX;
            }

            if (!TerraConfig.reducedConsoleMessages) {
                TerraMod.LOGGER.info(urltext);
            }

            //kumi systems request a meaningful user-agent
            URL url = new URL(urltext);
            URLConnection c = url.openConnection();
            c.addRequestProperty("User-Agent", TerraMod.USERAGENT);
            InputStream is = c.getInputStream();

            OsmReader reader = new OsmXmlReader(is, false);

            data = MapDataSetLoader.read(reader, true, true, true);

            is.close();

            this.buildData(data, region);

        } catch (Exception e) {
            TerraMod.LOGGER.error("Osm region download failed, " + e);
            e.printStackTrace();
            if (!TerraConfig.serverOverpassFallback.isEmpty() && !TerraConfig.serverOverpassFallback.equals(overpassInstance)) {
                TerraMod.LOGGER.error("We were using the main overpass instance (" + TerraConfig.serverOverpassDefault
                                      + "), switching to the backup one (" + TerraConfig.serverOverpassFallback + ')');
                overpassInstance = TerraConfig.serverOverpassFallback;
                cancelFallbackThread();
                fallbackCancellerThread = new Thread(() -> {
                    try {
                        TerraMod.LOGGER.info("Started fallback thread, it will try to switch back to the main endpoint in " + TerraConfig.overpassCheckDelay + "mn");
                        Thread.sleep(TerraConfig.overpassCheckDelay * 60000);
                        TerraMod.LOGGER.info("Trying to switch back to the main overpass endpoint");
                        overpassInstance = TerraConfig.serverOverpassDefault;
                    } catch (InterruptedException e1) {
                        TerraMod.LOGGER.info("Stopping fallback sleeping thread");
                    }

                });
                fallbackCancellerThread.setName("Overpass fallback check thread");
                fallbackCancellerThread.start();
                return this.regiondownload(region);
            } else {
                TerraMod.LOGGER.error("We were already using the backup Overpass endpoint or no backup endpoint is set, no structures will spawn");
            }

            return false;
        }

        double[] ll = this.projection.fromGeo(X, Y);
        double[] lr = this.projection.fromGeo(X + TILE_SIZE, Y);
        double[] ur = this.projection.fromGeo(X + TILE_SIZE, Y + TILE_SIZE);
        double[] ul = this.projection.fromGeo(X, Y + TILE_SIZE);

        //estimate bounds of region in terms of chunks
        int lowX = (int) Math.floor(Math.min(Math.min(ll[0], ul[0]), Math.min(lr[0], ur[0])) / CHUNK_SIZE);
        int highX = (int) Math.ceil(Math.max(Math.max(ll[0], ul[0]), Math.max(lr[0], ur[0])) / CHUNK_SIZE);
        int lowZ = (int) Math.floor(Math.min(Math.min(ll[1], ul[1]), Math.min(lr[1], ur[1])) / CHUNK_SIZE);
        int highZ = (int) Math.ceil(Math.max(Math.max(ll[1], ul[1]), Math.max(lr[1], ur[1])) / CHUNK_SIZE);

        for (Edge e : this.allEdges) {
            this.relevantChunks(lowX, lowZ, highX, highZ, e);
        }
        this.allEdges.clear();

        return true;
    }

    private void buildData(InMemoryMapDataSet data, Region region) throws IOException {
        // We create building geometries from relations and ways. Ways that are
        // part of multipolygon buildings may be tagged as buildings themselves,
        // however rendering them independently will fill the polygon holes they
        // are cutting out of the relations. Hence we store the ways found in
        // building relations to skip them later on when working on the ways.
        Set<OsmWay> usedRelationWays = new HashSet<>();
        // We use this to find all way members of relations.
        EntityFinder wayFinder = EntityFinders.create(data,
                EntityNotFoundStrategy.IGNORE);

        Collection<OsmRelation> relations = data.getRelations().valueCollection();
        Collection<OsmWay> ways = data.getWays().valueCollection();

        // Collect all areas
        for (OsmRelation relation : relations) {
            Map<String, String> tags = OsmModelUtil.getTagsAsMap(relation);
            MultiPolygon area = getPolygon(relation);
            if(area != null){
                Type type = typeCheck(tags);
                switch(type){
                    case BUILDING:
                        buildings.add(new BuildingElem(area, EType.relation, tags));
                        break;
                    case STREAM:
                        try{
                        waters.add(new WaterElem(area, EType.relation, tags));} catch(IllegalArgumentException e){
                            //TerraMod.LOGGER.debug("Illegal argument exception");
                        }
                        break;
                    case ROAD:
                        roads.add(new RoadElem(area, EType.relation, tags));
                }
            }//no need to remove from collection since we are only processing relations once
            try {
                wayFinder.findMemberWays(relation, usedRelationWays);
            } catch (EntityNotFoundException e) {
                // cannot happen (IGNORE strategy)
            }
        }

        // ... and also way areas
        for (OsmWay way : ways) {
            if (usedRelationWays.contains(way)) {
                ways.remove(way); //if the relations already sorted contain the way, it is removed from the collection for faster street sorting
                continue;
            }
            Map<String, String> tags = OsmModelUtil.getTagsAsMap(way);
            MultiPolygon area = getPolygon(way);
            if(area != null){
                Type type = typeCheck(tags);
                switch(type){
                    case BUILDING:
                        buildings.add(new BuildingElem(area, EType.area, tags));
                        break;
                    case STREAM:
                        try{
                            waters.add(new WaterElem(area, EType.area, tags));} catch(IllegalArgumentException e){
                            //TerraMod.LOGGER.debug("Illegal argument exception");
                        }
                        break;
                    case ROAD:
                        roads.add(new RoadElem(area, EType.area, tags));
                }
                ways.remove(way); //removes way if it is closed after processing
            }
        }

        // Collect streets and rails (non-closed ways)
        for (OsmWay way : ways) {

            Map<String, String> tags = OsmModelUtil.getTagsAsMap(way);

            Collection<LineString> paths = getLine(way);
            // Okay, this is a valid street

            //TODO: add street as polygon based on # of lanes? what to do with existing road generation code?

            for (LineString path : paths) {
                Type type = typeCheck(tags);
                switch(type){
                    case BUILDING:
                        buildings.add(new BuildingElem(path, EType.way, tags));
                        break;
                    case STREAM:
                        try{
                            waters.add(new WaterElem(path, EType.way, tags));} catch(IllegalArgumentException e){
                            //TerraMod.LOGGER.debug("Illegal argument exception");
                        }
                        break;
                    case ROAD:
                        roads.add(new RoadElem(path, EType.way, tags));
                    }
            }
        }
             //TODO: push attributes to generator classes
             //TODO: add all areas to ground array? or replace with land polygons or water polygons
             //TODO: create properties of region for lists
             //region.allRoads = roads;
             //region.allBuildings = buildings;
             //region.allWaters = waters;

        }




    void addWay(Element elem, Type type, byte lanes, Region region, Attributes attributes, byte layer) {
        double[] lastProj = null;
        if (elem.geometry != null) {
            for (Geom geom : elem.geometry) {
                if (geom == null) {
                    lastProj = null;
                } else {
                    double[] proj = this.projection.fromGeo(geom.lon, geom.lat);

                    if (lastProj != null) { //register as a road edge
                        this.allEdges.add(new Edge(lastProj[0], lastProj[1], proj[0], proj[1], type, lanes, region, attributes, layer));
                    }

                    lastProj = proj;
                }
            }
        }
    }

    Geom waterway(Element way, long id, Region region, Geom last) {
        if (way.geometry != null) {
            for (Geom geom : way.geometry) {
                if (geom != null && last != null) {
                    region.addWaterEdge(last.lon, last.lat, geom.lon, geom.lat, id);
                }
                last = geom;
            }
        }

        return last;
    }

    private void relevantChunks(int lowX, int lowZ, int highX, int highZ, Edge edge) {
        Coord start = new Coord((int) Math.floor(edge.slon / CHUNK_SIZE), (int) Math.floor(edge.slat / CHUNK_SIZE));
        Coord end = new Coord((int) Math.floor(edge.elon / CHUNK_SIZE), (int) Math.floor(edge.elat / CHUNK_SIZE));

        double startx = edge.slon;
        double endx = edge.elon;

        if (startx > endx) {
            Coord tmp = start;
            start = end;
            end = tmp;
            startx = endx;
            endx = edge.slon;
        }

        highX = Math.min(highX, end.x + 1);
        for (int x = Math.max(lowX, start.x); x < highX; x++) {
            double X = x * CHUNK_SIZE;
            int from = (int) Math.floor((edge.slope * Math.max(X, startx) + edge.offset) / CHUNK_SIZE);
            int to = (int) Math.floor((edge.slope * Math.min(X + CHUNK_SIZE, endx) + edge.offset) / CHUNK_SIZE);

            if (from > to) {
                int tmp = from;
                from = to;
                to = tmp;
            }

            for (int y = Math.max(from, lowZ); y <= to && y < highZ; y++) {
                this.assoiateWithChunk(new Coord(x, y), edge);
            }
        }
    }

    private void assoiateWithChunk(Coord c, Edge edge) {
        Set<Edge> list = this.chunks.get(c);
        if (list == null) {
            list = new HashSet<>();
            this.chunks.put(c, list);
        }
        list.add(edge);
    }

    //TODO: this algorithm is untested and may have some memory leak issues and also strait up copies code from earlier
    private void removeRegion(Region delete) {
        double X = delete.coord.x * TILE_SIZE;
        double Y = delete.coord.y * TILE_SIZE;

        double[] ll = this.projection.fromGeo(X, Y);
        double[] lr = this.projection.fromGeo(X + TILE_SIZE, Y);
        double[] ur = this.projection.fromGeo(X + TILE_SIZE, Y + TILE_SIZE);
        double[] ul = this.projection.fromGeo(X, Y + TILE_SIZE);

        //estimate bounds of region in terms of chunks
        int lowX = (int) Math.floor(Math.min(Math.min(ll[0], ul[0]), Math.min(lr[0], ur[0])) / CHUNK_SIZE);
        int highX = (int) Math.ceil(Math.max(Math.max(ll[0], ul[0]), Math.max(lr[0], ur[0])) / CHUNK_SIZE);
        int lowZ = (int) Math.floor(Math.min(Math.min(ll[1], ul[1]), Math.min(lr[1], ur[1])) / CHUNK_SIZE);
        int highZ = (int) Math.ceil(Math.max(Math.max(ll[1], ul[1]), Math.max(lr[1], ur[1])) / CHUNK_SIZE);

        for (int x = lowX; x < highX; x++) {
            for (int z = lowZ; z < highZ; z++) {
                Set<Edge> edges = this.chunks.get(new Coord(x, z));
                if (edges != null) {
                    edges.removeIf(edge -> edge.region.equals(delete));

                    if (edges.size() <= 0) {
                        this.chunks.remove(new Coord(x, z));
                    }
                }
            }
        }
    }

    public enum Type {
        IGNORE, ROAD, MINOR, SIDE, MAIN, INTERCHANGE, LIMITEDACCESS, FREEWAY, STREAM, RIVER, BUILDING, RAIL
        // ranges from minor to freeway for roads, use road if not known
    }

    public enum Attributes {
        ISBRIDGE, ISTUNNEL, NONE
    }

    public enum EType {
        invalid, node, way, relation, area
    }

    public static class noneBoolAttributes {
        public static String layer;
    }

    //integer coordinate class
    public static class Coord {
        public int x;
        public int y;

        private Coord(int x, int y) {
            this.x = x;
            this.y = y;
        }

        public int hashCode() {
            return (this.x * 79399) + (this.y * 100000);
        }

        public boolean equals(Object o) {
            Coord c = (Coord) o;
            return c.x == this.x && c.y == this.y;
        }

        public String toString() {
            return "(" + this.x + ", " + this.y + ')';
        }
    }

    public static class Edge {
        public Type type;
        public double slat;
        public double slon;
        public double elat;
        public double elon;
        public Attributes attribute;
        public byte layer_number;
        public double slope;
        public double offset;

        public byte lanes;

        Region region;

        private Edge(double slon, double slat, double elon, double elat, Type type, byte lanes, Region region, Attributes att, byte ly) {
            //slope must not be infinity, slight inaccuracy shouldn't even be noticible unless you go looking for it
            double dif = elon - slon;
            if (-NOTHING <= dif && dif <= NOTHING) {
                if (dif < 0) {
                    elon -= NOTHING;
                } else {
                    elon += NOTHING;
                }
            }

            this.slat = slat;
            this.slon = slon;
            this.elat = elat;
            this.elon = elon;
            this.type = type;
            this.attribute = att;
            this.lanes = lanes;
            this.region = region;
            this.layer_number = ly;

            this.slope = (elat - slat) / (elon - slon);
            this.offset = slat - this.slope * slon;
        }

        private double squareLength() {
            double dlat = this.elat - this.slat;
            double dlon = this.elon - this.slon;
            return dlat * dlat + dlon * dlon;
        }

        public int hashCode() {
            return (int) ((this.slon * 79399) + (this.slat * 100000) + (this.elat * 13467) + (this.elon * 103466));
        }

        public boolean equals(Object o) {
            Edge e = (Edge) o;
            return e.slat == this.slat && e.slon == this.slon && e.elat == this.elat && e.elon == e.elon;
        }

        public String toString() {
            return "(" + this.slat + ", " + this.slon + ',' + this.elat + ',' + this.elon + ')';
        }
    }

    public static class Member {
        EType type;
        long ref;
        String role;
    }

    public static class Geom {
        double lat;
        double lon;
    }

    public static class Element {
        EType type;
        long id;
        Map<String, String> tags;
        long[] nodes;
        Member[] members;
        Geom[] geometry;
    }

    public static class Data {
        float version;
        String generator;
        Map<String, String> osm3s;
        List<Element> elements;
    }

    public static class Elem {
        EType etype;
        Geometry geometry;
        Map<String, String> tags; //TODO: method to find id

        public Elem(Geometry geometry, EType etype, Map<String, String> tags){
            this.geometry = geometry;
            this.etype = etype;
            this.tags = tags;
        }
        public boolean isWithinBounds(double lon, double lat){
            Geometry point = new GeometryFactory().createPoint(new Coordinate(lon, lat));
            return this.geometry.overlaps(point);
        }
    }

    private static class RoadElem extends Elem{
        Type type;
        String name;
        Attributes attributes;
        byte lanes;
        byte layers;

        public RoadElem(Geometry geometry, EType etype, Map<String, String> tags) {
            super(geometry, etype, tags);
            //default params
            name = tags.get("name");
            attributes = Attributes.NONE;
            type = Type.ROAD;
            layers = 1;
            lanes = 2;

            String layerString = tags.get("layers");
            String laneString = tags.get("lanes");

            if(tags.get("tunnel") != null) attributes = Attributes.ISTUNNEL;
            if(tags.get("bridge") != null) attributes = Attributes.ISBRIDGE; //TODO: implement functionality

            if(attributes == Attributes.NONE)
                switch (tags.get("highway")) {
                    case "motorway":
                        type = Type.FREEWAY;
                    case "trunk":
                        type = Type.LIMITEDACCESS;
                    case "motorway_link":
                    case "trunk_link":
                        type = Type.INTERCHANGE;
                    case "primary_link":
                    case "secondary_link":
                    case "living_street":
                    case "bus_guideway":
                    case "service":
                    case "unclassified":
                    case "secondary":
                        type = Type.SIDE;
                    case "primary":
                    case "raceway":
                        type = Type.MAIN;
                    case "tertiary":
                    case "residential":
                        type = Type.MINOR;
                    default:
                        type = Type.ROAD;
                }

            if(tags.get("railway") != null) type = Type.RAIL; //TODO: check functionality

            if(layerString != null){
                try{
                    layers = Byte.parseByte(layerString);} catch (NumberFormatException e){
                    //TerraMod.LOGGER.debug("Could not find layers in OSM object " + name + ". Defaulting to 1");
                }
            }
            if (laneString != null){
                try{
                    lanes = Byte.parseByte(laneString);} catch(NumberFormatException e){
                    //TerraMod.LOGGER.debug("Could not find lanes in OSM object " + name + ". Defaulting to 2");
                }
            }
            //prevent super high # of lanes to prevent ridiculous results (prly a mistake if its this high anyways)
            if (lanes > 8)
                lanes = 8;

            // an interchange that doesn't have any lane tag should be defaulted to 2 lanes
            if (lanes < 2 && type == Type.INTERCHANGE)
                lanes = 2;

            // upgrade road type if many lanes (and the road was important enough to include a lanes tag)
            if (lanes > 2 && type == Type.MINOR)
                type = Type.MAIN;
        }
    }

    private static class WaterElem extends Elem{
        Type type;

        public WaterElem(Geometry geometry, EType etype, Map<String, String> tags) {
            super(geometry, etype, tags);
            String waterTags = tags.get("waterway");

            if("coastline".equals(tags.get("natural")) || "stream".equals(waterTags)) //TODO: create water polygon from coastline LineString
                type = Type.STREAM;
            else if ("river".equals(waterTags) || "canal".equals(waterTags))
                type = Type.RIVER;
            else throw new IllegalArgumentException(); //Invalid water element. Stop construction
        }
    }

    private static class BuildingElem extends Elem{
        Type type;
        short height;

        public BuildingElem(Geometry geometry, EType etype, Map<String, String> tags) {
            super(geometry, etype, tags);
            type = Type.BUILDING;
            try{
            height = Short.parseShort(tags.get("height"));} catch(NumberFormatException e){
                //TerraMod.LOGGER.debug("Could not find building height in OSM object " + name + ". Defaulting to 2");
            }
        }
    }

    //BuildingElem building = new BuildingElem(null, null, null, null);
    //Geometry geom = building.geometry;
    //Type elemtype = building.type;

}