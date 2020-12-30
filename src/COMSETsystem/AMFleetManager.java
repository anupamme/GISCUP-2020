package COMSETsystem;

import UserExamples.GlobalParameters;
import UserExamples.Region;
import UserExamples.TemporalUtils;
import com.uber.h3core.H3Core;
import com.uber.h3core.exceptions.DistanceUndefinedException;
import com.uber.h3core.exceptions.LineUndefinedException;

import java.io.*;
import java.util.*;
import java.lang.*;
import java.util.concurrent.ThreadLocalRandom;


public class AMFleetManager extends FleetManager {

    public enum agent_assignment_policy {
        Minimum_Driving_Distance,
        Minimum_Expiration,
        Waiting_Time_Resource
    }
    H3Core h3;
    private final int h3_resolution = 8;
    private TemporalUtils temporalUtils;
    private final Map<String, Integer> hexAddr2Region = new HashMap<>();
    private final Map<String, List<Intersection>> regionIntersectionMap = new HashMap<>();
    private final Map<String, List<Long>> regionAvailableAgentMap = new HashMap<>();
    private final Set<String> absentRegions = new HashSet<>();
    private final Map<String, Integer> regionResourceMap = new HashMap<>();
    private final List<String> regionList = new ArrayList<>();
    private final Map<String, List<Integer>> regionResourceTimeStamp = new HashMap<>();
    private final Map<String, List<Integer>> regionDestinationTimeStamp = new HashMap<>();
    private final Map<Long, Long> agentLastAppearTime = new HashMap<>();
    private final Map<Long, LocationOnRoad> agentLastLocation = new HashMap<>();
    private final Set<Long> availableAgent = new TreeSet<>(Comparator.comparingLong((Long id) -> id));
    private final Set<Resource> waitingResources = new TreeSet<>(Comparator.comparingLong((Resource r) -> r.id));
    private final Map<Long, Resource> resourceAssignment = new HashMap<>();
    private final Map<Long, LinkedList<Intersection>> agentRoutes = new HashMap<>();
    private final Map<Long, List<Resource>> agentResourceHistory = new HashMap<>();


    public AMFleetManager(CityMap map) {
        super(map);
        temporalUtils = new TemporalUtils(map.computeZoneId());
        try{
            h3 = H3Core.newInstance();
        }catch (IOException ex){
            ex.printStackTrace();
        }
        // read the predictions file
        readRegionList(GlobalParameters.regions_list);
        readPickupMatrix(GlobalParameters.pickup_pred_file);
        readDropOffMatrix(GlobalParameters.dropoff_pred_file);
        populateRegionIntersecitonMap();
        readRegionFrequencyFile(GlobalParameters.region_frequency);
    }

    private void readRegionList(String fileName) {
        try{
            File file = new File(fileName);
            BufferedReader reader = new BufferedReader(new FileReader(file));
            String tmp = null;
            while ((tmp = reader.readLine()) != null) {
                regionList.add(tmp);
            }
            reader.close();
        }catch (Exception ex){
            ex.printStackTrace();
        }
    }

    private void readPickupMatrix(String fileName){
        try{
            File file = new File(fileName);
            BufferedReader reader = new BufferedReader(new FileReader(file));
            for (String item : regionList)
                regionResourceTimeStamp.put(item, new ArrayList<Integer>());
            String tmp = null;
            while ((tmp = reader.readLine()) != null){
                String[] regionData = tmp.split(",");
                for(int i=0; i<regionData.length; i++){
                    String region_hex = regionList.get(i);
                    regionResourceTimeStamp.get(region_hex).add((int)Double.parseDouble(regionData[i]));
                }
            }
            reader.close();
        }catch (Exception ex){
            ex.printStackTrace();
        }
    }

    private void readDropOffMatrix(String fileName){
        try{
            File file = new File(fileName);
            BufferedReader reader = new BufferedReader(new FileReader(file));
            for (String item : regionList)
                regionDestinationTimeStamp.put(item, new ArrayList<Integer>());
            String tmp = null;
            while ((tmp = reader.readLine()) != null){
                String[] regionData = tmp.split(",");
                for(int i=0; i<regionData.length; i++){
                    String region_hex = regionList.get(i);
                    regionDestinationTimeStamp.get(region_hex).add((int)Double.parseDouble(regionData[i]));
                }
            }
            reader.close();
        }catch (Exception ex){
            ex.printStackTrace();
        }
    }

    private void populateRegionIntersecitonMap(){
        for(Intersection i : map.intersections().values()){
            double lat = i.latitude;
            double lng = i.longitude;
            String hexAddr = h3.geoToH3Address(lat, lng, h3_resolution);
            List<Integer> intersectionResource = new ArrayList<>();
            if (regionIntersectionMap.containsKey(hexAddr)){
                regionIntersectionMap.get(hexAddr).add(i);
            }
            else {
                List<Intersection> localVar = new ArrayList<>();
                localVar.add(i);
                regionIntersectionMap.put(hexAddr, localVar);
            }
        }
    }

    private void readRegionFrequencyFile(String fileName){
        File file = new File(fileName);
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(file));
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
        String tempString = null;
        while(true) {
            try {
                if (!((tempString = reader.readLine()) != null)) break;
            } catch (IOException e) {
                e.printStackTrace();
            }
            String[] elements = tempString.split(":");
            regionResourceMap.put(elements[0], Integer.parseInt(elements[1]));
        }
    }

    private double[] getLocationLatLon(LocationOnRoad location){
        double[] latLon = new double[2];
        double proportion = ((double) location.getStaticTravelTimeOnRoad()) / location.road.travelTime;

        if(proportion < 0)
            proportion = 0;
        if(proportion > 1)
            proportion = 1;

        latLon[0] =
                location.road.from.latitude + (location.road.to.latitude - location.road.from.latitude) * proportion;
        latLon[1] =
                location.road.from.longitude + (location.road.to.longitude - location.road.from.longitude) * proportion;
        return latLon;
    }

    private void addAgentToLeafNode(long agentId, LocationOnRoad currentLoc) {
        double[] latLon = getLocationLatLon(currentLoc);
        String hexAddr = h3.geoToH3Address(latLon[0],
                latLon[1], h3_resolution);
        if (regionAvailableAgentMap.containsKey(hexAddr)) {
            regionAvailableAgentMap.get(hexAddr).add(agentId);
        }
        else {
            List<Long> agentList = new ArrayList<>();
            agentList.add(agentId);
            regionAvailableAgentMap.put(hexAddr, agentList);
        }
    }

    /*
        1. Find the nearest cluster where to move to this agent.
        2. Find the point in the cluster and route the agent to that point.

        steps:
            leaf_node = get_leaf_node(currentLoc)
            different_policies: minimize_expiration, minimize_taxi_movement,

     */
    @java.lang.Override
    public void onAgentIntroduced(long agentId, LocationOnRoad currentLoc, long time) {
        agentLastAppearTime.put(agentId, time);
        agentLastLocation.put(agentId, currentLoc);
        availableAgent.add(agentId);
    }

    /*
        1.

     */
    Long getNearestAvailableAgent(Resource resource, long currentTime) {
        long earliest = Long.MAX_VALUE;
        Long bestAgent = null;
        for (Long id : availableAgent) {
            if (!agentLastLocation.containsKey(id)) continue;

            LocationOnRoad curLoc = getCurrentLocation(
                    agentLastAppearTime.get(id),
                    agentLastLocation.get(id),
                    currentTime);
            // Warning: map.travelTimeBetween returns the travel time based on speed limits, not
            // the dynamic travel time. Thus the travel time returned by map.travelTimeBetween may be different
            // than the actual travel time.
            long travelTime = map.travelTimeBetween(curLoc, resource.pickupLoc);
            long arriveTime = travelTime + currentTime;
            if (arriveTime < earliest) {
                bestAgent = id;
                earliest = arriveTime;
            }
        }

        if (earliest <= resource.expirationTime) {
            return bestAgent;
        } else {
            return null;
        }
    }

    /*
        1. If state == 'available':
            leaf_node: find the leaf node id of the location
            find the available agents in the node: select the one according to some policy.
        2. if state == 'dropped_off':
            call agent_introduced(agent_id, location)
        3. if state == 'expired':
            do nothing;
        4.
     */
    @java.lang.Override
    public AgentAction onResourceAvailabilityChange(Resource resource, ResourceState state, LocationOnRoad currentLoc,
                                                    long time) {
        System.out.println("Total #unavailable regions: " + absentRegions.size());
        for (Long agentId : agentResourceHistory.keySet()){
            System.out.println("agent, size: " + agentId + ": " + agentResourceHistory.get(agentId).size());
        }
        AgentAction action = AgentAction.doNothing();
        if (state == ResourceState.AVAILABLE) {
            Long nearestAgentId = getNearestAvailableAgent(resource, time);
            if (nearestAgentId == null) {
                waitingResources.add(resource);
            }
            else {
                resourceAssignment.put(nearestAgentId, resource);
                if (agentResourceHistory.containsKey(nearestAgentId))
                    agentResourceHistory.get(nearestAgentId).add(resource);
                else {
                    List<Resource> newList = new ArrayList<>();
                    newList.add(resource);
                    agentResourceHistory.put(nearestAgentId, newList);
                }
                agentRoutes.put(nearestAgentId, new LinkedList<>());
                availableAgent.remove(nearestAgentId);
                action = AgentAction.assignTo(nearestAgentId, resource.id);
            }
        }
        else if (state == ResourceState.DROPPED_OFF){
            Resource bestResource =  null;
            long earliest = Long.MAX_VALUE;
            for (Resource res : waitingResources) {
                // If res is in waitingResources, then it must have not expired yet
                // testing null pointer exception
                // Warning: map.travelTimeBetween returns the travel time based on speed limits, not
                // the dynamic travel time. Thus the travel time returned by map.travelTimeBetween may be different
                // than the actual travel time.
                long travelTime = map.travelTimeBetween(currentLoc, res.pickupLoc);

                // if the resource is reachable before expiration
                long arriveTime = time + travelTime;
                if (arriveTime <= res.expirationTime && arriveTime < earliest) {
                    earliest = arriveTime;
                    bestResource = res;
                }
            }
            if (bestResource != null) {
                waitingResources.remove(bestResource);
                action = AgentAction.assignTo(resource.assignedAgentId, bestResource.id);
            } else {
                availableAgent.add(resource.assignedAgentId);
                action = AgentAction.doNothing();
            }
            resourceAssignment.put(resource.assignedAgentId, bestResource);
            agentLastLocation.put(resource.assignedAgentId, currentLoc);
            agentLastAppearTime.put(resource.assignedAgentId, time);
        }
        else if (state == ResourceState.PICKED_UP) {
            agentRoutes.put(resource.assignedAgentId, new LinkedList<>());
        }
        else if (state == ResourceState.EXPIRED) {
            waitingResources.remove(resource);
            if (resource.assignedAgentId != -1) {
                agentRoutes.put(resource.assignedAgentId, new LinkedList<>());
                availableAgent.add(resource.assignedAgentId);
                resourceAssignment.remove(resource.assignedAgentId);
            }
        }
        return action;
    }

    private Map<String, Float> calculate_probabilities(String source_h3, Map<String, Double> region_resource){
        Map<String, Float> region_cumulative = new HashMap<>();
        float sum = 0;
        for(String candidate_region : region_resource.keySet()){
            double candidate_resource = region_resource.get(candidate_region);
            int distance;
            try {
                distance = h3.h3Distance(source_h3, candidate_region);
            } catch (DistanceUndefinedException e) {
                e.printStackTrace();
                distance = 1; // XXX
            }
//            System.out.println("Distance between regions " + source_h3 + ", " + candidate_region + ": " + distance);
            float netValue = (float) candidate_resource/(distance + 1) ;
            sum += netValue;
            region_cumulative.put(candidate_region, netValue);
        }
        // calculate probabilities at this point.
        for (String candidate_region : region_cumulative.keySet()){
            float candidate_val = region_cumulative.get(candidate_region);
            region_cumulative.put(candidate_region, candidate_val/sum);
        }
        return region_cumulative;
    }

    class Tuple{
        public String key;
        public float val;

        public Tuple(String _key, float _val){
            this.key = _key;
            this.val = _val;
        }
    }

    private String sample_regions(Map<String, Float> regionProbabilities){
        ArrayList<Tuple> region_list = new ArrayList<>();
        for (String region : regionProbabilities.keySet()){
            Float val = regionProbabilities.get(region);
            Tuple t = new Tuple(region, val);
            region_list.add(t);
        }
        region_list.sort(new Comparator<Tuple>() {
            @Override
            public int compare(Tuple o1, Tuple o2) {
                if (o1.val > o2.val){
                    return 1;
                }
                else if (o1.val < o2.val) {
                    return -1;
                }
                else {
                    return 0;
                }
            }
        });
//        System.out.println("Region List: " + region_list);
        double randomNum = ThreadLocalRandom.current().nextDouble(0, 1);
//        System.out.println("random number: " + randomNum);
        float sum = 0;
        for (Tuple t : region_list) {
            sum += t.val;
            if (sum >= randomNum) {
                return t.key;
            }
        }
        return null;
    }

    private Intersection selectIntersection(String h3_code){
        if (regionIntersectionMap.containsKey(h3_code)){
            List<Intersection> intersectionList = regionIntersectionMap.get(h3_code);
            int randomNum = ThreadLocalRandom.current().nextInt(0, intersectionList.size());
            return intersectionList.get(randomNum);
        }
        else {
            System.out.println("Warning h3code not found: " + h3_code);
            return null;
        }
    }

    private double getRegionWeightTemporal(String region_h3, long time){
        int timeIndex = temporalUtils.findTimeIntervalIndex(time);
        int k = GlobalParameters.timeHorizon / GlobalParameters.timeInterval;
        double weight = 1.0;
        List<Integer> resourceTimeStamp = regionResourceTimeStamp.get(region_h3);
        List<Integer> destinationTimeStamp = regionDestinationTimeStamp.get(region_h3);
        for(int i = timeIndex; i < timeIndex + k; i++){
            if(i < resourceTimeStamp.size())
                weight += Math.pow(0.8, i - timeIndex) * (resourceTimeStamp.get(i) - GlobalParameters.lambda * destinationTimeStamp.get(i));
        }
        if(weight < 0)
            weight = 1.0;
        return weight;
    }



    private int getRegionWeight(String h3_code){
        if (regionResourceMap.containsKey(h3_code)){
            return regionResourceMap.get(h3_code);
        }
        else {
            absentRegions.add(h3_code);
//            System.out.println("Could not find the region in the resource map: " + h3_code);
            return 1;
        }
    }

    LinkedList<Intersection> getRandomRoute(long agentId, LocationOnRoad currentLoc, long time) {

//        System.out.println("finding next intersection for: " + agentId);
        double[] latLon = getLocationLatLon(currentLoc);
        String hexAddr = h3.geoToH3Address(latLon[0], latLon[1], h3_resolution);
//        System.out.println("finding next intersection for region: " + hexAddr);
        List<String> nearest_h3 = new ArrayList<>();
        if (regionIntersectionMap.containsKey(hexAddr)) {
            List<String> candidate_h3 = h3.kRing(hexAddr, 1);
            for (String region: candidate_h3) {
                if (region.equals(hexAddr)) {
                    continue;
                }
                if (regionIntersectionMap.containsKey(region)) {
                    nearest_h3.add(region);
                } else {
                    absentRegions.add(region);
//                    System.out.println("Ignoring the absent region: " + region);
                }
            }
        }
        else {
//            System.out.println("Region not found: " + hexAddr);
            int radius = 2;
            do {
                List<String> candidate_regions = h3.kRing(hexAddr, radius);
                for (String reg : candidate_regions){
                    if (reg.equals(hexAddr)) {
                        continue;
                    }
                    if (regionIntersectionMap.containsKey(reg)){
                        nearest_h3.add(reg);
                    }
                }
                radius += 2 * radius;
            }while (nearest_h3.size() == 0);
        }
        Map<String, Double> region_map = new HashMap<>();
        for (String nearest_region : nearest_h3){
            double resource_estimate = getRegionWeight(nearest_region);
//            double resource_estimate = getRegionWeightTemporal(nearest_region, time);
            region_map.put(nearest_region, resource_estimate);
        }
//        System.out.println("Candidate Resource: " + region_map);
        Map<String, Float> h3_probabilities = calculate_probabilities(hexAddr, region_map);
//        System.out.println("Region Probabilities: " + h3_probabilities);
        String h3_selected = sample_regions(h3_probabilities);
//        System.out.println("next region: " + h3_selected);
        Intersection selected_intersection = selectIntersection(h3_selected);
//        System.out.println("next intersection: " + selected_intersection.id);
        Intersection sourceIntersection = currentLoc.road.to;
        LinkedList<Intersection> path = map.shortestTravelTimePath(sourceIntersection, selected_intersection);
//        System.out.println("Selected Path: " + path);
        path.poll(); // ignore the first destination as it is the source one
//        Intersection destination = path.poll();
//        System.out.println("poll.destination: " + destination);
        return path;
    }

    LinkedList<Intersection> planRoute(long agentId, LocationOnRoad currentLocation, long time) {
        Resource assignedRes = resourceAssignment.get(agentId);

        if (assignedRes != null) {
            Intersection sourceIntersection = currentLocation.road.to;
            Intersection destinationIntersection = assignedRes.pickupLoc.road.from;
            LinkedList<Intersection> shortestTravelTimePath = map.shortestTravelTimePath(sourceIntersection,
                    destinationIntersection);
            shortestTravelTimePath.poll(); // Ensure that route.get(0) != currentLocation.road.to.
            return shortestTravelTimePath;
        } else {
            return getRandomRoute(agentId, currentLocation, time);
        }
    }

    /*
        Build a rudimentary predictor:
            input: 15 digit h3 code, timestamp
            output: number of requests.
        What we know: number of agents in that h3 region: we keep a count of that
        In each region: there are two stats: number of predicted requests, number of available agents.
        We select the regions where the difference > 0.
        For each region we compute: free_slots/distance e.g. if a hexagon has 5 free slots and is at the distance of
        2 units then this number is 5/2.
        Based on these numbers we sample the a region as a destination region.
        We randomly pick an intersection within the region and select it as a destination intersection.

        Steps:
        1. h3_code = h3util.get_current_h3_code(currentLoc, resolution=res)
        2. max_nearest_neighbours = 10
        3. nearest_1 = h3.k_ring(h3_code, 1)
        4. nearest_2 = h3.k_ring(h3_code, 2)
        5. predicted_requests = get_predictions_request(nearest_1 + nearest_2)
        6. regions_sel = selected_regions(nearest_1 + nearest_2,  predicted_requests)
        7. region_sel = sample_region(regions_sel)
        8. intersection_sel = get_an intersection(region_sel)
        9. return intersection_sel

     */
    @java.lang.Override
    public Intersection onReachIntersection(long agentId, long time, LocationOnRoad currentLoc) {
        if (agentId == 240902L && time == 1464800008L) {
            System.out.println("here");
        }
        agentLastAppearTime.put(agentId, time);
        LinkedList<Intersection> route = agentRoutes.getOrDefault(agentId, new LinkedList<>());
        if (route.isEmpty()) {
            route = planRoute(agentId, currentLoc, time);
            agentRoutes.put(agentId, route);
        }
        Intersection nextLocation = route.poll();
        Road nextRoad = currentLoc.road.to.roadTo(nextLocation);
        LocationOnRoad locationOnRoad = LocationOnRoad.createFromRoadStart(nextRoad);
        agentLastLocation.put(agentId, locationOnRoad);
        return nextLocation;
    }

    LinkedList<Intersection> planRouteToTarget(LocationOnRoad source, LocationOnRoad destination) {
        Intersection sourceIntersection = source.road.to;
        Intersection destinationIntersection = destination.road.from;
        LinkedList<Intersection> shortestTravelTimePath = map.shortestTravelTimePath(sourceIntersection,
                destinationIntersection);
        shortestTravelTimePath.poll(); // Ensure that route.get(0) != currentLocation.road.to.
        return shortestTravelTimePath;
    }

    /*
        1. find the shortest path to the destination
     */
    @java.lang.Override
    public Intersection onReachIntersectionWithResource(long agentId, long time, LocationOnRoad currentLoc, Resource resource) {
        LinkedList<Intersection> route = planRouteToTarget(currentLoc, resource.dropOffLoc);
        return route.poll();
    }
}
