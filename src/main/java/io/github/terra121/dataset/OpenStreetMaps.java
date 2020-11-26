package io.github.terra121.dataset;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.terra121.TerraConfig;
import io.github.terra121.TerraMod;
import io.github.terra121.projection.GeographicProjection;
import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.lang.reflect.Array;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

//BEGIN PROTOCODE

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.MultiPolygon;

import de.topobyte.adt.geo.BBox;
import de.topobyte.osm4j.core.access.OsmInputException;
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

import javax.management.Attribute;


public class OsmData
{

    //Query examples:
    //Check water at 41.2, -87.6: boolean isWater = pointWithinPolygons(water, -87.6, 41.2);

    public OsmData() { //TODO: constructor

    }
        // This is the region we would like to render
        BBox bbox = new BBox(13.45546, 52.51229, 13.46642, 52.50761); //TODO: region download and cache

        // Define a query to retrieve some data
        String queryTemplate = "http://overpass-api.de/api/interpreter?data=(node(%f,%f,%f,%f);<;>;);out;";
        String query = String.format(queryTemplate, bbox.getLat2(),
                bbox.getLon1(), bbox.getLat1(), bbox.getLon2());

        // Open a stream
        InputStream input;

    {
        try {
            input = new URL(query).openStream();
        } catch (IOException e) {
            e.printStackTrace(); //TODO: log errors
        }
    }

    // Create a reader and read all data into a data set
        OsmReader reader = new OsmXmlReader(input, false);
        InMemoryMapDataSet data;

    {
        try {
            data = MapDataSetLoader.read(reader, true, true,
                            true);
        } catch (OsmInputException e) {
            e.printStackTrace();
        }
    }
    // The data set will be used as entity provider when building geometries

    //TODO: call buildData() in region cache code
    //buildData();

    // This is a set of values for the 'highway' key of ways that we will render
    // as streets
    /*
    private Set<String> validHighways = new HashSet<>(
            Arrays.asList("primary", "secondary", "tertiary",
                    "residential", "living_street"));

    */


    // We build the geometries to be rendered during construction and store them
    // in these fields so that we don't have to recompute everything when
    // rendering.
    private List<Geometry> buildings = new ArrayList<>();
    private List<Geometry> water = new ArrayList<>();
    private List<LineString> streets = new ArrayList<>();
    private Map<LineString, String> names = new HashMap<>();

    private void buildData()
    {
        // We create building geometries from relations and ways. Ways that are
        // part of multipolygon buildings may be tagged as buildings themselves,
        // however rendering them independently will fill the polygon holes they
        // are cutting out of the relations. Hence we store the ways found in
        // building relations to skip them later on when working on the ways.
        Set<OsmWay> buildingRelationWays = new HashSet<>();
        // We use this to find all way members of relations.
        EntityFinder wayFinder = EntityFinders.create(data,
                EntityNotFoundStrategy.IGNORE);

        // Collect buildings from relation areas...
        for (OsmRelation relation : data.getRelations().valueCollection()) {
            Map<String, String> tags = OsmModelUtil.getTagsAsMap(relation);
            MultiPolygon area = getPolygon(relation);
            addAreaToArray(tags, area);
                try {
                    wayFinder.findMemberWays(relation, buildingRelationWays);
                } catch (EntityNotFoundException e) {
                    // cannot happen (IGNORE strategy)
                }

        }
        // ... and also from way areas
        for (OsmWay way : data.getWays().valueCollection()) {
            if (buildingRelationWays.contains(way)) {
                continue;
            }
            Map<String, String> tags = OsmModelUtil.getTagsAsMap(way);
            MultiPolygon area = getPolygon(way);
            addAreaToArray(tags, area);
        }

        // Collect streets
        for (OsmWay way : data.getWays().valueCollection()) {
            Map<String, String> tags = OsmModelUtil.getTagsAsMap(way);

            String highway = tags.get("highway");
            if (highway == null) { //skips if not of type highway
                continue;
            }

            Collection<LineString> paths = getLine(way);

            switch (highway){ //TODO: Hashmap (?) storage code
                case "motorway":
                    //freeway
                    break;
                case "trunk":
                    //limitedaccess
                    break;
                case "motorway_link":
                case "trunk_link":
                    //intersection
                    break;
                case "primary_link":
                case "secondary_link":
                case "living_street":
                case "bus_guideway":
                case "service":
                case "unclassified":
                case "secondary":
                    //side
                    break;
                case "primary":
                case "raceway":
                    //main
                    break;
                case "tertiary":
                case "residential":
                    //minor
                    break;
                default:
                    //TODO: default classification
            }

            // Okay, this is a valid street
            for (LineString path : paths) {
                streets.add(path); //TODO: add street as polygon based on # of lanes? what to do with existing road generation code?
            }

            // If it has a name, store it for labeling
            String name = tags.get("name");
            if (name == null) {
                continue;
            }
            for (LineString path : paths) {
                names.put(path, name);
            }
        }


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

    private void addAreaToArray(Map<String, String> tags, MultiPolygon area){
        if (area != null)
        switch (tags.containsKey()){
            case "building":
                buildings.add(area);
                break;
            case "natural":
            case "waterway":
            case "water":
                water.add(area); //TODO: refine water code after testing
                break;
        }
    }

    private boolean pointWithinPolygons(List<Geometry> array, double lon, double lat){
        //compute position within polygons of array
        for (Geometry polygon : array){
            if(pointWithinBounds(polygon, new Coordinate(lon, lat))) return true;
        }
        return false;
    }

    private boolean pointWithinBounds(Geometry polygon, Coordinate coord){ //pointWithinBounds(path, new Coordinate(lat, lon));
        Geometry point = new GeometryFactory().createPoint(coord);
        return polygon.overlaps(point);
    }

}

//END PROTOCODE


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

    private List<Geometry> buildings = new ArrayList<>();
    private List<Geometry> waters = new ArrayList<>();
    private List<Geometry> streets = new ArrayList<>();
    private Map<Geometry, String> names = new HashMap<>();
    private Map<Geometry, Attributes> attributes = new HashMap<>();
    private Map<Geometry, Type> types = new HashMap<>();
    private Map<Geometry, Byte> lanes = new HashMap<>();
    private Map<Geometry, Byte> layers = new HashMap<>();
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

    private boolean addAreaToArray(Map<String, String> tags, MultiPolygon area){
        if (area != null) {
            for (String tag : tags.keySet()) {
                switch (tag) {
                    case "building": //building areas
                        if (this.doBuildings)
                            buildings.add(area);
                        break;
                    case "natural": //water areas
                    case "waterway":
                    case "water":
                        if (this.doWater)
                            waters.add(area); //TODO: refine water code after testing
                        break;
                    case "tunnel":
                    case "bridge": //skip if tunnel or bridge
                        break;
                    case "highway": //street areas
                        if(this.doRoad){
                            Type type = Type.ROAD;
                            String name = tags.get("name");
                            String layerString = tags.get("layers");
                            String laneString = tags.get("lanes");
                            Attributes attribute = Attributes.NONE;
                            byte layersProvided = 1;
                            byte lanesProvided = 2;
                            if(tags.get("tunnel") != null) attribute = Attributes.ISTUNNEL;
                            if(tags.get("bridge") != null) attribute = Attributes.ISBRIDGE;
                            if(attribute == Attributes.NONE) type = highwayChecker(tags);
                            if(layerString != null){
                                layersProvided = Byte.parseByte(layerString);
                            }
                            if (laneString != null){
                                lanesProvided = Byte.parseByte(laneString);
                            }
                            streets.add(area); //change streets list to type Geometry since we are dealing with LineStrings and Polygons
                            if(name != null)
                            names.put(area, name); //same with names and types maps
                            types.put(area, type);
                            lanes.put(area, lanesProvided);//TODO: lanes saved so that we can mark them automatically?
                            layers.put(area, layersProvided);
                            attributes.put(area, attribute);
                        }
                        break;
                }
            }
            return true;
        }
        else {
            return false;}
    }

    private Type highwayChecker(Map<String, String> tags){
        switch (tags.get("highway")){
            case "motorway":
                return Type.FREEWAY;
            case "trunk":
                return Type.LIMITEDACCESS;
            case "motorway_link":
            case "trunk_link":
                return Type.INTERCHANGE;
            case "primary_link":
            case "secondary_link":
            case "living_street":
            case "bus_guideway":
            case "service":
            case "unclassified":
            case "secondary":
                return Type.SIDE;
            case "primary":
            case "raceway":
                return Type.MAIN;
            case "tertiary":
            case "residential":
                return Type.MINOR;
            default:
                return Type.ROAD;
        }
    }

    private boolean pointWithinPolygons(List<Geometry> array, double lon, double lat){
        //compute position within polygons of array
        for (Geometry polygon : array){
            if(pointWithinBounds(polygon, new Coordinate(lon, lat))) return true;
        }
        return false;
    }

    private boolean pointWithinBounds(Geometry polygon, Coordinate coord){ //pointWithinBounds(path, new Coordinate(lat, lon));
        Geometry point = new GeometryFactory().createPoint(coord);
        return polygon.overlaps(point);
    }

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

        // Collect buildings from relation areas...
        // Collect all areas? Yes
        for (OsmRelation relation : relations) {
            Map<String, String> tags = OsmModelUtil.getTagsAsMap(relation);
            MultiPolygon area = getPolygon(relation);
            addAreaToArray(tags, area); //no need to remove from collection since we are only processing relations once
            try {
                wayFinder.findMemberWays(relation, usedRelationWays);
            } catch (EntityNotFoundException e) {
                // cannot happen (IGNORE strategy)
            }
        }
        // ... and also from way areas
        for (OsmWay way : ways) {
            if (usedRelationWays.contains(way)) {
                ways.remove(way); //if the relations already sorted contain the way, it is removed from the collection for faster street sorting
                continue;
            }
            Map<String, String> tags = OsmModelUtil.getTagsAsMap(way);
            MultiPolygon area = getPolygon(way);
            if (addAreaToArray(tags, area))
                ways.remove(way); //removes way if it is closed after processing
        }

        // Collect streets and rails (non-closed ways)
        for (OsmWay way : ways) {
            Attributes attributes = Attributes.NONE;
            Type type = Type.ROAD;

            Map<String, String> tags = OsmModelUtil.getTagsAsMap(way);

            String highway = tags.get("highway");
            String istunnel = tags.get("tunnel");
            // to be implemented
            String isbridge = tags.get("bridge");

            if (!this.doRoad) { //skips if roads are disabled
                continue;
            }

            type = highwayChecker(tags); //get highway type

            Collection<LineString> paths = getLine(way);
            // Okay, this is a valid street
            for (LineString path : paths) {
                streets.add(path); //TODO: add street as polygon based on # of lanes? what to do with existing road generation code?
            }

            // If it has a name, store it for labeling
            String name = tags.get("name");
            if (name == null) {
                continue;
            }
            for (LineString path : paths) {
                names.put(path, name);
                types.put(path, type);
            }
        }

        //*





                    Attributes attributes = Attributes.NONE;
                    Type type = Type.ROAD;

                    if (waterway != null) {
                        type = Type.STREAM;
                        if ("river".equals(waterway) || "canal".equals(waterway)) {
                            type = Type.RIVER;
                        }

                    }

                    if (building != null) {
                        type = Type.BUILDING;
                    }

                    if (istunnel != null && "yes".equals(istunnel)) {

                        attributes = Attributes.ISTUNNEL;

                    } else if (isbridge != null && "yes".equals(isbridge)) {

                        attributes = Attributes.ISBRIDGE;

                    } else {

                        // totally skip classification if it's a tunnel or bridge. this should make it more efficient.
                        if (highway != null && attributes == Attributes.NONE) {
                            switch (highway) {
                                case "motorway":
                                    type = Type.FREEWAY;
                                    break;
                                case "trunk":
                                    type = Type.LIMITEDACCESS;
                                    break;
                                case "motorway_link":
                                case "trunk_link":
                                    type = Type.INTERCHANGE;
                                    break;
                                case "secondary":
                                    type = Type.SIDE;
                                    break;
                                case "primary":
                                case "raceway":
                                    type = Type.MAIN;
                                    break;
                                case "tertiary":
                                case "residential":
                                    type = Type.MINOR;
                                    break;
                                default:
                                    if ("primary_link".equals(highway) ||
                                        "secondary_link".equals(highway) ||
                                        "living_street".equals(highway) ||
                                        "bus_guideway".equals(highway) ||
                                        "service".equals(highway) ||
                                        "unclassified".equals(highway)) {
                                        type = Type.SIDE;
                                    }
                                    break;
                            }
                        }
                    }
                    //get lane number (default is 2)
                    String slanes = elem.tags.get("lanes");
                    String slayer = elem.tags.get("layers");
                    byte lanes = 2;
                    byte layer = 1;

                    if (slayer != null) {

                        try {

                            layer = Byte.parseByte(slayer);

                        } catch (NumberFormatException e) {

                            // default to layer 1 if bad format

                        }

                    }

                    if (slanes != null) {

                        try {

                            lanes = Byte.parseByte(slanes);

                        } catch (NumberFormatException e) {

                        } //default to 2, if bad format
                    }

                    //prevent super high # of lanes to prevent ridiculous results (prly a mistake if its this high anyways)
                    if (lanes > 8) {
                        lanes = 8;
                    }

                    // an interchange that doesn't have any lane tag should be defaulted to 2 lanes
                    if (lanes < 2 && type == Type.INTERCHANGE) {
                        lanes = 2;
                    }

                    // upgrade road type if many lanes (and the road was important enough to include a lanes tag)
                    if (lanes > 2 && type == Type.MINOR) {
                        type = Type.MAIN;
                    }

                    this.addWay(elem, type, lanes, region, attributes, layer);

                 else { //TODO: placeholder code. learn what this is for!
                    unusedWays.add(elem);}

             else if (elem.type == EType.relation && elem.members != null && elem.tags != null) {

                if (this.doWater) {
                    String naturalv = elem.tags.get("natural");
                    String waterv = elem.tags.get("water");
                    String wway = elem.tags.get("waterway");

                    if (waterv != null || (naturalv != null && "water".equals(naturalv)) || (wway != null && "riverbank".equals(wway))) {
                        for (Member member : elem.members) {
                            if (member.type == EType.way) {
                                Element way = allWays.get(member.ref);
                                if (way != null) {
                                    this.waterway(way, elem.id + 3600000000L, region, null);
                                    unusedWays.remove(way);
                                }
                            }
                        }
                        continue;
                    }
                }
                if (this.doBuildings && elem.tags.get("building") != null) {
                    for (Member member : elem.members) {
                        if (member.type == EType.way) {
                            Element way = allWays.get(member.ref);
                            if (way != null) {
                                this.addWay(way, Type.BUILDING, (byte) 1, region, Attributes.NONE, (byte) 0);
                                unusedWays.remove(way);
                            }
                        }
                    }
                }

            } else if (elem.type == EType.area) {
                ground.add(elem.id);
            }
        }
*/




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
}