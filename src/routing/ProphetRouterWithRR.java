package routing;

import java.util.*;
import core.*;

/**
 * Implementation of PRoPHET router as described in
 * <I>Probabilistic routing in intermittently connected networks</I>
 * by Anders Lindgren et al.
 */
public class ProphetRouterWithRR extends ActiveRouter implements CVDetectionEngine, DropRepDetectionEngine {

    /**
     * delivery predictability initialization constant
     */
    public static final double P_INIT = 0.75;
    /**
     * delivery predictability transitivity scaling constant default value
     */
    public static final double DEFAULT_BETA = 0.25;
    /**
     * delivery predictability aging constant
     */
    public static final double GAMMA = 0.98;

    /**
     * Prophet router's setting namespace ({@value})
     */
    public static final String PROPHET_NS = "ProphetRouterWithRR";
    public static final String AI_S = "ai";
    public static final String MD_S = "md";
    public static final String ALPHA_CV = "alphaCV";

    public static final int DEFAULT_AI = 1;
    public static final double DEFAULT_MD = 0.2;
    public static final double DEFAULT_ALPHA = 0.9;
    /**
     * Number of seconds in time unit -setting id ({@value}). How many seconds
     * one time unit is when calculating aging of delivery predictions. Should
     * be tweaked for the scenario.
     */
    public static final String SECONDS_IN_UNIT_S = "secondsInTimeUnit";

    /**
     * Transitivity scaling constant (beta) -setting id ({@value}). Default
     * value for setting is {@link #DEFAULT_BETA}.
     */
    public static final String BETA_S = "beta";

    /**
     * the value of nrof seconds in time unit -setting
     */
    private int secondsInTimeUnit;
    /**
     * value of beta setting
     */
    private double beta;

    /**
     * delivery predictabilities
     */
    private Map<DTNHost, Double> preds;
    /**
     * last delivery predictability update (sim)time
     */
    private double lastAgeUpdate;

    // added - Retiring replicants's attributes -
    private int nrOfReps = 0; // inisialisasi jumlah replikasi
    private int nrOfDrops = 0; // inisialisasi jumlah drop
    private int limit = 1; // inisialisasi limit
    protected double CV = 0; // inisialisasi CV
    private int AI;
    private double MD;
    public double ALPHA;
    private Map<Connection, Integer> connLimit; // store connection along with limit
    protected List<CVTime> cvList = new ArrayList<>();
    protected List<Double> dropRepList = new ArrayList<>();
    private Map<String, ACK_TTL> receiptBuffer; // buffer that save receipt(ACK purposes)
    /**
     * message that should be deleted
     */
    private Set<String> messageReadytoDelete;

    /**
     * Constructor. Creates a new message router based on the settings in the
     * given Settings object.
     *
     * @param s The settings object
     */
    public ProphetRouterWithRR(Settings s) {
        super(s);
        Settings prophetSettings = new Settings(PROPHET_NS);
        secondsInTimeUnit = prophetSettings.getInt(SECONDS_IN_UNIT_S);
        if (prophetSettings.contains(BETA_S)) {
            beta = prophetSettings.getDouble(BETA_S);
        } else {
            beta = DEFAULT_BETA;
        }
        if (prophetSettings.contains(AI_S)) {
            AI = prophetSettings.getInt(AI_S);
        } else {
            AI = DEFAULT_AI;
        }
        if (prophetSettings.contains(MD_S)) {
            MD = prophetSettings.getDouble(MD_S);
        } else {
            MD = DEFAULT_MD;
        }
        if (prophetSettings.contains(ALPHA_CV)) {
            ALPHA = prophetSettings.getDouble(ALPHA_CV);
        } else {
            ALPHA = DEFAULT_ALPHA;
        }
        initPreds();
        initConnLimit();
        this.receiptBuffer = new HashMap<>();
        this.messageReadytoDelete = new HashSet<>();
    }

    /**
     * Copyconstructor.
     *
     * @param r The router prototype where setting values are copied from
     */
    protected ProphetRouterWithRR(ProphetRouterWithRR r) {
        super(r);
        this.secondsInTimeUnit = r.secondsInTimeUnit;
        this.beta = r.beta;
        this.AI = r.AI;
        this.MD = r.MD;
        this.ALPHA = r.ALPHA;
        initPreds();
        initConnLimit();
        this.receiptBuffer = new HashMap<>();
        this.messageReadytoDelete = new HashSet<>();

    }

    /**
     * Initializes predictability hash
     */
    private void initPreds() {
        this.preds = new HashMap<DTNHost, Double>();
    }

    /**
     * Initializes Connection and Limit hash
     */
    private void initConnLimit() {
        this.connLimit = new HashMap<Connection, Integer>();
    }

    @Override
    public void changedConnection(Connection con) {
        if (con.isUp()) {
            DTNHost otherHost = con.getOtherNode(getHost());
            updateDeliveryPredFor(otherHost);
            updateTransitivePreds(otherHost);

            connLimit.put(con, this.limit);
            System.out.println(this.limit);
            Collection<Message> thisMessageList = getMessageCollection();
            ProphetRouterWithRR othRouter = (ProphetRouterWithRR) otherHost.getRouter();

            // Exchange receipt buffer
            Map<String, ACK_TTL> peerReceiptBuffer = othRouter.getReceiptBuffer();

            for (Map.Entry<String, ACK_TTL> entry : peerReceiptBuffer.entrySet()) {
                if (!receiptBuffer.containsKey(entry.getKey())) {
                    receiptBuffer.put(entry.getKey(), entry.getValue());
                }
            }
            for (Message m : thisMessageList) {
                // Delete message that have a receipt
                if (receiptBuffer.containsKey(m.getId())) {
                    messageReadytoDelete.add(m.getId());
                }
            }

            for (String m : messageReadytoDelete) {
                if (isSending(m)) {
                    List<Connection> conList = getConnections();
                    for (Connection conn : conList) {
                        if (conn.getMessage() != null && conn.getMessage().getId() == m) {
                            conn.abortTransfer();;
                            break;
                        }
                    }
                }
                deleteMessage(m, false);
            }
            messageReadytoDelete.clear();
        } else {
            DTNHost otherHost = con.getOtherNode(getHost());
            double newCV = calculateCV(con, otherHost);
            CVTime cvValue = new CVTime(newCV, SimClock.getTime());
            cvList.add(cvValue);
            if (newCV <= this.CV) {
                this.limit = this.limit + AI;
            } else {
                this.limit = (int) Math.ceil(this.limit * MD);
            }
            this.CV = newCV;
            connLimit.remove(con);
            messageReadytoDelete.clear();
        }
    }

    public Map<String, ACK_TTL> getReceiptBuffer() {
        return receiptBuffer;
    }

    /**
     * Updates delivery predictions for a host.
     * <CODE>P(a,b) = P(a,b)_old + (1 - P(a,b)_old) * P_INIT</CODE>
     *
     * @param host The host we just met
     */
    private void updateDeliveryPredFor(DTNHost host) {
        double oldValue = getPredFor(host);
        double newValue = oldValue + (1 - oldValue) * P_INIT;
        preds.put(host, newValue);
    }

    /**
     * Returns the current prediction (P) value for a host or 0 if entry for the
     * host doesn't exist.
     *
     * @param host The host to look the P for
     * @return the current P value
     */
    public double getPredFor(DTNHost host) {
        ageDeliveryPreds(); // make sure preds are updated before getting
        if (preds.containsKey(host)) {
            return preds.get(host);
        } else {
            return 0;
        }
    }

    /**
     * Updates transitive (A->B->C) delivery predictions.      <CODE>P(a,c) = P(a,c)_old + (1 - P(a,c)_old) * P(a,b) * P(b,c) * BETA
     * </CODE>
     *
     * @param host The B host who we just met
     */
    private void updateTransitivePreds(DTNHost host) {
        MessageRouter otherRouter = host.getRouter();
        assert otherRouter instanceof ProphetRouterWithRR : "PRoPHET only works "
                + " with other routers of same type";

        double pForHost = getPredFor(host); // P(a,b)
        Map<DTNHost, Double> othersPreds
                = ((ProphetRouterWithRR) otherRouter).getDeliveryPreds();

        for (Map.Entry<DTNHost, Double> e : othersPreds.entrySet()) {
            if (e.getKey() == getHost()) {
                continue; // don't add yourself
            }

            double pOld = getPredFor(e.getKey()); // P(a,c)_old
            double pNew = pOld + (1 - pOld) * pForHost * e.getValue() * beta;
            preds.put(e.getKey(), pNew);
        }
    }

    /**
     * Ages all entries in the delivery predictions.
     * <CODE>P(a,b) = P(a,b)_old * (GAMMA ^ k)</CODE>, where k is number of time
     * units that have elapsed since the last time the metric was aged.
     *
     * @see #SECONDS_IN_UNIT_S
     */
    private void ageDeliveryPreds() {
        double timeDiff = (SimClock.getTime() - this.lastAgeUpdate)
                / secondsInTimeUnit;

        if (timeDiff == 0) {
            return;
        }

        double mult = Math.pow(GAMMA, timeDiff);
        for (Map.Entry<DTNHost, Double> e : preds.entrySet()) {
            e.setValue(e.getValue() * mult);
        }

        this.lastAgeUpdate = SimClock.getTime();
    }

    /**
     * Returns a map of this router's delivery predictions
     *
     * @return a map of this router's delivery predictions
     */
    private Map<DTNHost, Double> getDeliveryPreds() {
        ageDeliveryPreds(); // make sure the aging is done
        return this.preds;
    }

    @Override
    public void update() {
        super.update();
        if (!canStartTransfer() || isTransferring()) {
            return; // nothing to transfer or is currently transferring 
        }

        // try messages that could be delivered to final recipient
        if (exchangeDeliverableMessages() != null) {
            return;
        }

        tryOtherMessages();
    }

    // Untuk menghitung jumlah drop pesan
    @Override
    protected boolean makeRoomForMessage(int size) {
        if (size > this.getBufferSize()) {
            return false; // message too big for the buffer
        }

        int freeBuffer = this.getFreeBufferSize();
        /* delete messages from the buffer until there's enough space */
        while (freeBuffer < size) {
            Message m = getOldestMessage(true); // don't remove msgs being sent

            if (m == null) {
                return false; // couldn't remove any more messages
            }

            /* delete message from the buffer as "drop" */
            deleteMessage(m.getId(), true);
            this.nrOfDrops++;

            freeBuffer += m.getSize();
        }

        return true;
    }

    @Override
    public Message messageTransferred(String id, DTNHost from) {
        Message msg = super.messageTransferred(id, from);
        this.nrOfReps++; // add -> this.nrOfReps++
        // - ACK -
        if (isFinalDest(msg, this.getHost()) && !receiptBuffer.containsKey(msg.getId())) {
            ACK_TTL ack = new ACK_TTL(SimClock.getTime(), msg.getTtl());
            receiptBuffer.put(msg.getId(), ack);
        }
        return msg;
    }

    // check if this host is final destination
    public boolean isFinalDest(Message m, DTNHost aHost) {
        if (m.getTo().equals(aHost)) {
            return true;
        }
        return m.getTo().equals(aHost);
    }

    // Untuk mengembalikan nilai CV
    private double calculateCV(Connection con, DTNHost peer) {
        ProphetRouterWithRR othRouter = (ProphetRouterWithRR) peer.getRouter();
        int hopCount = messagestotalHops();
        int drops = this.nrOfDrops + othRouter.nrOfDrops;
        int reps = this.nrOfReps + othRouter.nrOfReps + hopCount;
        // reset
        nrOfDrops = 0;
        nrOfReps = 0;
        // added
        double rasio;
        if (reps != 0) {
            rasio = (double) drops / (double) reps;
            dropRepList.add(rasio);
            return (ALPHA * rasio) + ((1.0 - ALPHA) * CV);
        } else {
            //rasio = (double) drops / 0.0001;
            //rasio = 1;
            return this.CV;
        }
    }

    // Counting total hops of messages
    private int messagestotalHops() {
        Collection<Message> msg = getMessageCollection();
        int totalHops = 0;
        if (!msg.isEmpty()) {
            for (Message m : msg) {
                if (!(m.getHopCount() == 0)) {
                    totalHops += (m.getHopCount() - 1);
                }
            }
        }
        return totalHops;
    }

    @Override
    protected Connection tryAllMessagesToAllConnections() {
        List<Connection> connections = getConnections();
        if (connections.size() == 0 || this.getNrofMessages() == 0) {
            return null;
        }

        List<Message> messages
                = new ArrayList<Message>(this.getMessageCollection());
        this.sortByQueueMode(messages);

        return tryMessagesToConnections(messages, connections);
    }

    /**
     * Tries to send all other messages to all connected hosts ordered by their
     * delivery probability
     *
     * @return The return value of {@link #tryMessagesForConnected(List)}
     */
    private Tuple<Message, Connection> tryOtherMessages() {
        List<Tuple<Message, Connection>> messages
                = new ArrayList<Tuple<Message, Connection>>();

        Collection<Message> msgCollection = getMessageCollection();

        /* for all connected hosts collect all messages that have a higher
		   probability of delivery by the other host */
        for (Connection con : getConnections()) {
            DTNHost other = con.getOtherNode(getHost());
            ProphetRouterWithRR othRouter = (ProphetRouterWithRR) other.getRouter();

            if (othRouter.isTransferring()) {
                continue; // skip hosts that are transferring
            }

            for (Message m : msgCollection) {
                if (othRouter.hasMessage(m.getId())) {
                    continue; // skip messages that the other one has
                }
                if (othRouter.getPredFor(m.getTo()) > getPredFor(m.getTo())) {
                    // the other node has higher probability of delivery
                    messages.add(new Tuple<Message, Connection>(m, con));
                }
            }
        }

        if (messages.size() == 0) {
            return null;
        }
        // sort the messsage-connection tuples
        Collections.sort(messages, new TupleComparator());
        return tryMessagesForConnected(messages); // try to send messages
    }

    protected int startTransfer(Message m, Connection con) {
        int retVal;

        if (!con.isReadyForTransfer()) {
            return TRY_LATER_BUSY;
        }
        if (connLimit.containsKey(con)) {
            retVal = con.startTransfer(getHost(), m);
            if (retVal == RCV_OK) { // started transfer
                addToSendingConnections(con);
                int remainingLimit = connLimit.get(con) - 1;
                if (remainingLimit != 0) {
                    connLimit.replace(con, remainingLimit);
                } else {
                    connLimit.remove(con);
                }
            } else if (deleteDelivered && retVal == DENIED_OLD && m.getTo() == con.getOtherNode(this.getHost())) {
                /* final recipient has already received the msg -> delete it*/
                this.deleteMessage(m.getId(), false);

            }
            return retVal;
        }
        return DENIED_UNSPECIFIED;
    }

    @Override
    public List<CVTime> getCongestionValue() {
        return this.cvList;
    }

    @Override
    public List<Double> getDropRep() {
        return this.dropRepList;
    }

    /**
     * Comparator for Message-Connection-Tuples that orders the tuples by their
     * delivery probability by the host on the other side of the connection
     * (GRTRMax)
     */
    private class TupleComparator implements Comparator<Tuple<Message, Connection>> {

        public int compare(Tuple<Message, Connection> tuple1,
                Tuple<Message, Connection> tuple2) {
            // delivery probability of tuple1's message with tuple1's connection
            double p1 = ((ProphetRouterWithRR) tuple1.getValue().
                    getOtherNode(getHost()).getRouter()).getPredFor(
                    tuple1.getKey().getTo());
            // -"- tuple2...
            double p2 = ((ProphetRouterWithRR) tuple2.getValue().
                    getOtherNode(getHost()).getRouter()).getPredFor(
                    tuple2.getKey().getTo());

            // bigger probability should come first
            if (p2 - p1 == 0) {
                /* equal probabilities -> let queue mode decide */
                return compareByQueueMode(tuple1.getKey(), tuple2.getKey());
            } else if (p2 - p1 < 0) {
                return -1;
            } else {
                return 1;
            }
        }
    }

    @Override
    public RoutingInfo getRoutingInfo() {
        ageDeliveryPreds();
        RoutingInfo top = super.getRoutingInfo();
        RoutingInfo ri = new RoutingInfo(preds.size()
                + " delivery prediction(s)");

        for (Map.Entry<DTNHost, Double> e : preds.entrySet()) {
            DTNHost host = e.getKey();
            Double value = e.getValue();

            ri.addMoreInfo(new RoutingInfo(String.format("%s : %.6f",
                    host, value)));
        }

        top.addMoreInfo(ri);
        return top;
    }

    @Override
    public MessageRouter replicate() {
        ProphetRouterWithRR r = new ProphetRouterWithRR(this);
        return r;
    }
}
