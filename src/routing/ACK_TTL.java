package routing;

/**
 *
 * @author Desy
 */
public class ACK_TTL {

    public double SimTime;
    public double startTime;

    public ACK_TTL(double start, double t) { // start = waktu mulai, t = waktu simulasi
        startTime = start;
        SimTime = t;
    }
}
