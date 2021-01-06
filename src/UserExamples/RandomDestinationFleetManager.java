package UserExamples;

import COMSETsystem.*;
import UserExamples.TemporalUtils;
import com.uber.h3core.H3Core;
import com.uber.h3core.exceptions.DistanceUndefinedException;

import java.io.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class RandomDestinationFleetManager extends FleetManager {
    H3Core h3;
    private final int h3_resolution = 3;
    private TemporalUtils temporalUtils;
    private final Map<String, List<Intersection>> regionIntersectionMap = new HashMap<>();
    private final Map<String, Float> regionResourceMap = new HashMap<>();
    private final Map<String, List<Integer>> regionResourceTimeStamp = new HashMap<>();
    private final Map<String, List<Integer>> regionDestinationTimeStamp = new HashMap<>();
    private final List<String> regionList = new ArrayList<>();
    private final Map<Long, List<Resource>> agentResourceHistory = new HashMap<>();
    private final Map<Long, Long> agentLastAppearTime = new HashMap<>();
    private final Map<Long, LocationOnRoad> agentLastLocation = new HashMap<>();
    private final Map<Long, Resource> resourceAssignment = new HashMap<>();
    private final Set<Resource> waitingResources = new TreeSet<>(Comparator.comparingLong((Resource r) -> r.id));
    private final Set<Long> availableAgent = new TreeSet<>(Comparator.comparingLong((Long id) -> id));
    private final Map<Long, Random> agentRnd = new HashMap<>();

    Map<Long, LinkedList<Intersection>> agentRoutes = new HashMap<>();

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
            regionResourceMap.put(elements[0], Float.parseFloat(elements[1]));
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
                if (regionList.size() < regionData.length) {
                    System.out.println("Size of Region list and region data: " + regionList.size()
                            + ", " + regionData.length);
                }
                for(int i=0; i<regionData.length; i++){
                    if (i < regionList.size()){
                        String region_hex = regionList.get(i);
                        regionResourceTimeStamp.get(region_hex).add((int)Double.parseDouble(regionData[i]));
                    }
                    else {
                        int a = 1;
//                        System.out.println("Region not found in region list for index: " + i);
                    }
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
                if (regionList.size() < regionData.length) {
                    System.out.println("Size of Region list and region data: " + regionList.size()
                            + ", " + regionData.length);
                }
                for(int i=0; i<regionData.length; i++){
                    if (i < regionList.size()) {
                        String region_hex = regionList.get(i);
                        regionDestinationTimeStamp.get(region_hex).add((int) Double.parseDouble(regionData[i]));
                    }
                }
            }
            reader.close();
        }catch (Exception ex){
            ex.printStackTrace();
        }
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

    /**
     * The simulation calls onAgentIntroduced to notify the **FleetManager** that a new agent has been randomly
     * placed and is available for assignment.
     * @param agentId a unique id for each agent and can be used to associated information with agents.
     * @param currentLoc the current location of the agent.
     * @param time the simulation time.
     */
    @Override
    public void onAgentIntroduced(long agentId, LocationOnRoad currentLoc, long time) {
        agentLastAppearTime.put(agentId, time);
        agentLastLocation.put(agentId, currentLoc);
        availableAgent.add(agentId);
    }

    private Map<Integer, Integer> getStats() {
        Map<Integer, Integer> output = new HashMap<>();
        for (Long agentId : agentResourceHistory.keySet()){
            int val = agentResourceHistory.get(agentId).size();
            if (output.containsKey(val))
                output.put(val, output.get(val) + 1);
            else
                output.put(val, 1);
        }
        return output;
    }

    /**
     * The simulation calls this method to notify the **FleetManager** that the resource's state has changed:
     * + resource becomes available for pickup
     * + resource expired
     * + resource has been dropped off by its assigned agent
     * + resource has been picked up by an agent.
     * @param resource This object contains information about the Resource useful to the fleet manager
     * @param state the new state of the resource
     * @param currentLoc current location of the resources
     * @param time the simulation time
     * @return AgentAction that tells the agents what to do.
     */
    @Override
    public AgentAction onResourceAvailabilityChange(Resource resource,
                                                    ResourceState state,
                                                    LocationOnRoad currentLoc,
                                                    long time) {

        Map<Integer, Integer> agentStats = getStats();
        System.out.println("Agent Stats: " + agentStats);
        AgentAction action = AgentAction.doNothing();

        if (state == ResourceState.AVAILABLE) {
            Long assignedAgent = getNearestAvailableAgent(resource, time);
            if (assignedAgent != null) {
                resourceAssignment.put(assignedAgent, resource);
                agentRoutes.put(assignedAgent, new LinkedList<>());
                availableAgent.remove(assignedAgent);
                action = AgentAction.assignTo(assignedAgent, resource.id);
                if (agentResourceHistory.containsKey(assignedAgent))
                    agentResourceHistory.get(assignedAgent).add(resource);
                else {
                    List<Resource> newList = new ArrayList<>();
                    newList.add(resource);
                    agentResourceHistory.put(assignedAgent, newList);
                }
            } else {
                waitingResources.add(resource);
            }
        } else if (state == ResourceState.DROPPED_OFF) {
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
        } else if (state == ResourceState.EXPIRED) {
            waitingResources.remove(resource);
            if (resource.assignedAgentId != -1) {
                agentRoutes.put(resource.assignedAgentId, new LinkedList<>());
                availableAgent.add(resource.assignedAgentId);
                resourceAssignment.remove(resource.assignedAgentId);
            }
        } else if (state == ResourceState.PICKED_UP) {
            agentRoutes.put(resource.assignedAgentId, new LinkedList<>());
        }

        return action;
    }

    /**
     * Calls to this method notifies that an agent has reach an intersection and is ready for new travel directions.
     * This is called whenever any agent without an assigned resources reaches an intersection. This method allows
     * the **FleetManager** to plan any agent's cruising path, the path it takes when it has no assigned resource.
     * The intention is that the **FleetManager** will plan the cruising, to minimize the time it takes to
     * reach resources for pickup.
     * @param agentId unique id of the agent
     * @param time current simulation time.
     * @param currentLoc current location of the agent.
     * @return the next intersection for the agent to navigate to.
     */
    @Override
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

    /**
     * Calls to this method notifies that an agent with an picked up resource reaches an intersection.
     * This method allows the **FleetMangaer** to plan the route of the agent to the resource's dropoff point.
     * @param agentId the unique id of the agent
     * @param time current simulation time
     * @param currentLoc current location of agent
     * @param resource information of the resource associated with the agent.
     * @return the next intersection for the agent to navigate to.
     */
    @Override
    public Intersection onReachIntersectionWithResource(long agentId, long time, LocationOnRoad currentLoc,
                                                        Resource resource) {
        agentLastAppearTime.put(agentId, time);

        LinkedList<Intersection> route = agentRoutes.getOrDefault(agentId, new LinkedList<>());

        if (route.isEmpty()) {
            route = planRouteToTarget(resource.pickupLoc, resource.dropOffLoc);
            agentRoutes.put(agentId, route);
        }

        Intersection nextLocation = route.poll();
        Road nextRoad = currentLoc.road.to.roadTo(nextLocation);
        LocationOnRoad locationOnRoad = LocationOnRoad.createFromRoadStart(nextRoad);
        agentLastLocation.put(agentId, locationOnRoad);
        return nextLocation;
    }

    Long getNearestAvailableAgent(Resource resource, long currentTime) {
        long earliest = Long.MAX_VALUE;
        Long bestAgent = null;
        List<RandomDestinationFleetManager.Tuple> eligibleAgents = new ArrayList<>();
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
            int numberOfRides;
            if (GlobalParameters.agent_assignment == GlobalParameters.agentAssignmentPolicy.Nearest){
                numberOfRides = 1;
            }
            else if (GlobalParameters.agent_assignment == GlobalParameters.agentAssignmentPolicy.Fair) {
                if (agentResourceHistory.containsKey(id))
                    numberOfRides = agentResourceHistory.get(id).size();
                else
                    numberOfRides = 0;
            }
            else
                numberOfRides = -1;
            if (arriveTime <= resource.expirationTime) {
                eligibleAgents.add(new RandomDestinationFleetManager.Tuple(id.toString(), arriveTime * numberOfRides));
            }
        }
        if (eligibleAgents.size() > 0) {
            eligibleAgents.sort(new Comparator<RandomDestinationFleetManager.Tuple>() {
                @Override
                public int compare(RandomDestinationFleetManager.Tuple o1, RandomDestinationFleetManager.Tuple o2) {
                    if (o1.val > o2.val) {
                        return 1;
                    } else if (o1.val < o2.val) {
                        return -1;
                    } else {
                        return 0;
                    }
                }
            });
//            System.out.println("Eligible Agents: " + eligibleAgents);
            return Long.parseLong(eligibleAgents.get(0).key);
        }
        else
            return null;
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
            if (GlobalParameters.agent_direction == GlobalParameters.agentDirectionalPolicy.Random){
                return getRandomRoute(agentId, currentLocation);
            }
            else if (GlobalParameters.agent_direction == GlobalParameters.agentDirectionalPolicy.FixedFrequency) {
                return getFrequencyRoute(agentId, currentLocation);
            }
            else if (GlobalParameters.agent_direction == GlobalParameters.agentDirectionalPolicy.TemporalFrequency) {
                return getFrequencyTemporalRoute(agentId, currentLocation, time);
            }
            else
                return getRandomRoute(agentId, currentLocation);
        }
    }

    LinkedList<Intersection> planRouteToTarget(LocationOnRoad source, LocationOnRoad destination) {
        Intersection sourceIntersection = source.road.to;
        Intersection destinationIntersection = destination.road.from;
        LinkedList<Intersection> shortestTravelTimePath = map.shortestTravelTimePath(sourceIntersection,
                destinationIntersection);
        shortestTravelTimePath.poll(); // Ensure that route.get(0) != currentLocation.road.to.
        return shortestTravelTimePath;
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

    private float getRegionWeight(String h3_code){
        if (regionResourceMap.containsKey(h3_code)){
            return regionResourceMap.get(h3_code);
        }
        else {
//            System.out.println("Could not find the region in the resource map: " + h3_code);
            return 1;
        }
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

        public String toString() {
            return this.key + ":" + this.val;
        }
    }

    private String sample_regions(Map<String, Float> regionProbabilities){
        ArrayList<RandomDestinationFleetManager.Tuple> region_list = new ArrayList<>();
        for (String region : regionProbabilities.keySet()){
            Float val = regionProbabilities.get(region);
            RandomDestinationFleetManager.Tuple t = new RandomDestinationFleetManager.Tuple(region, val);
            region_list.add(t);
        }
        region_list.sort(new Comparator<RandomDestinationFleetManager.Tuple>() {
            @Override
            public int compare(RandomDestinationFleetManager.Tuple o1, RandomDestinationFleetManager.Tuple o2) {
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
        for (RandomDestinationFleetManager.Tuple t : region_list) {
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
        if (resourceTimeStamp == null | destinationTimeStamp == null){
            weight =  1;
            return weight;
        }
        else {
            for (int i = timeIndex; i < timeIndex + k; i++) {
                if (i < resourceTimeStamp.size())
                    weight += Math.pow(0.8, i - timeIndex) * (resourceTimeStamp.get(i) - GlobalParameters.lambda * destinationTimeStamp.get(i));
            }
            if (weight < 0)
                weight = 1.0;
            return weight;
        }
    }

    LinkedList<Intersection> getRandomRoute(long agentId, LocationOnRoad currentLocation) {
        Random random = agentRnd.getOrDefault(agentId, new Random(agentId));
        agentRnd.put(agentId, random);

        Intersection sourceIntersection = currentLocation.road.to;
        int destinationIndex = random.nextInt(map.intersections().size());
        Intersection[] intersectionArray =
                map.intersections().values().toArray(new Intersection[0]);
       Intersection destinationIntersection = intersectionArray[destinationIndex];
        if (destinationIntersection == sourceIntersection) {
            // destination cannot be the source
            // if destination is the source, choose a neighbor to be the destination
            Road[] roadsFrom =
                    sourceIntersection.roadsMapFrom.values().toArray(new Road[0]);
            destinationIntersection = roadsFrom[0].to;
        }
        LinkedList<Intersection> shortestTravelTimePath = map.shortestTravelTimePath(sourceIntersection,
                destinationIntersection);
        shortestTravelTimePath.poll(); // Ensure that route.get(0) != currentLocation.road.to.
        return shortestTravelTimePath;
    }

    LinkedList<Intersection> getFrequencyTemporalRoute(long agentId, LocationOnRoad currentLoc, long time) {
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
                    int a = 1;
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
            double resource_estimate = getRegionWeightTemporal(nearest_region, time);
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

    LinkedList<Intersection> getFrequencyRoute(long agentId, LocationOnRoad currentLoc) {

//        System.out.println("finding next intersection for: " + agentId);
        double[] latLon = getLocationLatLon(currentLoc);
        String hexAddr = h3.geoToH3Address(latLon[0], latLon[1], h3_resolution);
//        System.out.println("finding next intersection for region: " + hexAddr);
        List<String> nearest_h3 = new ArrayList<>();
        if (regionIntersectionMap.containsKey(hexAddr)) {
            List<String> candidate_h3 = h3.kRing(hexAddr, 1);
//            System.out.println("candidate h3: " + candidate_h3);
            for (String region: candidate_h3) {
                if (region.equals(hexAddr)) {
                    continue;
                }
                if (regionIntersectionMap.containsKey(region)) {
                    nearest_h3.add(region);
                } else {
                    int a = 1;
//                    System.out.println("Ignoring the absent region in intersection region: " + region);
                }
            }
        }
        else
//            System.out.println("Region not found: " + hexAddr);
        if (nearest_h3.size() == 0) {
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
//        System.out.println("Nearest Regions: " + nearest_h3);
        Map<String, Double> region_map = new HashMap<>();
        for (String nearest_region : nearest_h3){
            double resource_estimate = getRegionWeight(nearest_region);
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
        Intersection destination = path.poll();
//        System.out.println("poll.destination: " + destination);
        return path;
    }

    public RandomDestinationFleetManager(CityMap map) {
        super(map);
        try{
            h3 = H3Core.newInstance();
        }catch (IOException ex){
            ex.printStackTrace();
        }
        populateRegionIntersecitonMap();
        readRegionFrequencyFile(GlobalParameters.region_frequency);
//        temporalUtils = new TemporalUtils(map.computeZoneId());
//        readPickupMatrix(GlobalParameters.pickup_pred_file);
//        readDropOffMatrix(GlobalParameters.dropoff_pred_file);
//        readRegionList(GlobalParameters.regions_list);
    }
}
