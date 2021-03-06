package UserExamples;

import java.time.LocalDateTime;

/**
 * The default parameters shared with the whole program
 */
public class GlobalParameters {
    public static final int timeInterval = 5;
    public static final int timeHorizon = 20;
    public static final int numOfTimeIntervalsPerDay = 288;
    public static final int numOfIntersectionTimeIntervalPerDay = 48;
    public static final int cruising_threshold = 600;
    public static final int k = 6; // the size of neighbor layers
    public static final int n = 5; // the size of candidate regions
    public static final double gamma = -1.5;
    public static final double lambda = 0.8;
//    public static final String region_frequency = "datasets/h3_regions_7.txt";
//    public static final String region_frequency = "datasets/h3_regions_3.txt";
//    public static final String region_frequency = "datasets/h3_regions_noisy_4.txt";
    public static final String region_frequency = "datasets/h3_regions_noisy_geo_4.txt";
    public static final String regions_list = "model/regions.txt";
    public static final String pickup_pred_file = "model/pickup_pred_1_6.txt";
    public static final String dropoff_pred_file = "model/dropoff_pred_1_6.txt";
    public static final String intersectionResourceFile = "model/intersectionPickup_1_6_pred.txt";
    public static final LocalDateTime temporal_start_datetime = LocalDateTime.of(2016, 1, 1, 0, 0,0);
    public static final LocalDateTime temporal_end_datetime = LocalDateTime.of(2016, 7, 1, 0, 0, 0);
    public enum agentAssignmentPolicy {
        Nearest,
        Fair
    }
    public enum agentDirectionalPolicy {
        Random,
        FixedFrequency,
        TemporalFrequency
    }
    public boolean addNoise = false;
    public static agentAssignmentPolicy agent_assignment = agentAssignmentPolicy.Nearest;
    public static agentDirectionalPolicy agent_direction = agentDirectionalPolicy.FixedFrequency;
}
