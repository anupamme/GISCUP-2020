package UserExamples;

import COMSETsystem.Intersection;
import COMSETsystem.Resource;

import java.util.*;

/**
 * A region is a hexagon divided by H3 library
 */
public class Region {
    public String hexAddr; // The index of region
    public List<Integer> resourceQuantity = new ArrayList<>(); // The predicted resource quantity list in the region
    public List<Integer> destinationQuantity = new ArrayList<>(); // The predicted dropoff points list in the region


    public Region(String hexAddr){
        this.hexAddr = hexAddr;
    }
}
