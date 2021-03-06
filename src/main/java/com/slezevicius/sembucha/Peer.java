package com.slezevicius.sembucha;

import java.io.BufferedInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.InvalidParameterException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.zip.DataFormatException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Peer class that represents a single connection with one peer
 * for one particular torrent. Its behaviour is determined by the
 * peer manager who owns it.
 */
public class Peer extends Thread { 
    private PeerManager peerManager;
    private InetAddress ip;
    private int port;
    private Socket sock;
    private DataOutputStream out;
    private BufferedInputStream in;

    /**
     * foundByPeerServer is true if it was initialized by the peer server, otherwise false.
     * This influences how the main loop behaves with regards to handshakes.
     */
    private boolean foundByPeerServer;
    private volatile byte[] peerBitfield;

    /**
     * The pieceQueue holds Request objects for all the received pieces. The peer manager periodically checks
     * the queue and informs the file manager to write the piece that was received.
     */
    private volatile ConcurrentLinkedQueue<Request> pieceQueue = new ConcurrentLinkedQueue<>();

    /**
     * The requestQueue holds Request objects for all requests that the peer (the leecher) has made. The peer
     * manager periodically checks this list and orders the Peer to send a piece if the conditions are right.
     */
    private volatile ConcurrentLinkedQueue<Request> requestQueue = new ConcurrentLinkedQueue<>();

    /**
     * The haveQueue holds Integer objects representing the indices that the peer has sent HAVE messages for.
     */
    private volatile ConcurrentLinkedQueue<Integer> haveQueue = new ConcurrentLinkedQueue<>();

    /**
     * The cancelList stores int arrays of form {idx, begin, length} where each element represents a cancelled piece.
     */
    private List<int[]> cancelList = new ArrayList<>();
    private volatile boolean amChoking = true;
    private volatile boolean amInterested = false;
    private volatile boolean peerChocking = true;
    private volatile boolean peerInterested = false;
    private volatile boolean keepRunning = true;
    private volatile int requestCount = 0;
    private volatile boolean receivedBitfield = false;
    private volatile boolean receivedFirstMessage = false;
    private boolean LTEP = false;
    private boolean DHT = false;
    private byte[] infoHash;
    private Logger log;

    /**
     * Constructor for initializing peer that was received from the tracker.
     * Only initializes the fields and does nothing else until instructed.
     * @param pair
     * @param peerManager
     */
    Peer(Pair<InetAddress, Integer> pair, PeerManager peerManager) throws IOException {
        log = LogManager.getFormatterLogger(Peer.class);
        foundByPeerServer = false;
        this.peerManager = peerManager;
        infoHash = peerManager.getInfoHash();
        peerBitfield = new byte[peerManager.getBitfieldLength()];
        ip = pair.getLeft();
        port = pair.getRight();
        sock = new Socket();
        sock.connect(new InetSocketAddress(ip, port), 1000);
        //sock = new Socket(ip, port);
        out = new DataOutputStream(sock.getOutputStream());
        //in = new DataInputStream(sock.getInputStream());
        in = new BufferedInputStream(sock.getInputStream());
        log.trace("%s initialized", toString());
    }

    /**
     * Constructor for initializing peer that was found by the peer server.
     * Initializes all the fields it can and also initializes the handshake
     * in order to identify the torrent that the peer wants.
     * @param sock
     * @throws IOException
     * @throws DataFormatException
     * @throws InterruptedException
     */
    Peer(Socket sock)
            throws IOException, DataFormatException, InterruptedException {
        log = LogManager.getFormatterLogger(Peer.class);
        foundByPeerServer = true;
        ip = sock.getInetAddress();
        port = sock.getPort();
        this.sock = sock;
        out = new DataOutputStream(sock.getOutputStream());
        //in = new DataInputStream(sock.getInputStream());
        in = new BufferedInputStream(sock.getInputStream());
        receiveHandshake();
        log.trace("Peer[ip=%s, port=%d] initialized through a socket", ip.toString(), port);
    }

    Peer() {
        //Empty constructor for testing
    }

    /**
     * Fills out the fields that previously required a peerManager.
     * This has to be instantly called after the Peer Socket constructor
     * after determining which peerManager the Peer belongs to based on infoHash.
     * @param peerManager
     */
    public void introducePeerManager(PeerManager peerManager) {
        this.peerManager = peerManager;
        synchronized(this) {
            peerBitfield = new byte[peerManager.getBitfieldLength()];
        }
        log.trace("%s added peer manager", toString());
    }

    /**
     * Main loop for the peer thread. Keeps listening for new orders
     * from the peer manager and executes based on those orders.
     */
    @Override
    public void run() {
        log.trace("%s in the peer main loop", toString());
        try {
            if (!foundByPeerServer) {
                sendHandshake();
                receiveHandshake();
            } else {
                sendHandshake();
            }
            log.trace("%s starting main loop", toString());
            while (true) {
                Thread.sleep(50);
                synchronized(this) {
                    if (!keepRunning) {
                        log.info("%s shutting down", toString());
                        shutdownSockets();
                        return;
                    }
                }
                // if (in.available() > 0) {
                // }
                int length = 0;
                for (int i = 3; i >= 0; i--) {
                    int val = in.read();
                    if (val == -1) {
                        throw new IOException("EOF was reached");
                    }
                    length += val * Math.pow(256, i);
                }
                if (length == 0) { //Keep alive
                    continue;
                }
                int payloadLength = length - 1;
                int id = in.read();
                if (id == -1) {
                    throw new IOException("EOF was reached");
                }
                log.debug("%s received message with id: %d", toString(), id);
                if (id != 5) { //Needed in order to check whether a bitfield message is first if it is received.
                    synchronized(this) {
                        receivedFirstMessage = true;
                    }
                }
                switch (id) {
                    case 0:
                        synchronized(this) {
                            peerChocking = true;
                        }
                        break;
                    case 1:
                        synchronized(this) {
                            peerChocking = false;
                        }
                        break;
                    case 2:
                        synchronized(this) {
                            peerInterested = true;
                        }
                        break;
                    case 3:
                        synchronized(this) {
                            peerInterested = false;
                        }
                        break;
                    case 4:
                        receiveHave();
                        break;
                    case 5:
                        receiveBitfield(payloadLength);
                        break;
                    case 6:
                        receiveRequest();
                        break;
                    case 7:
                        receivePiece(payloadLength);
                        break;
                    case 8:
                        receiveCancel();
                        break;
                    case 9:
                        receivePort();
                        break;
                    case 20:
                        receiveExtension(payloadLength);
                        break;
                    default:
                        log.fatal("%s unknown message id %d", toString(), id);
                        shutdownSockets();
                        return;
                }
            }
        } catch (IOException | InterruptedException | DataFormatException e) {
            log.error("%s received an error", toString());
            log.error(e.getMessage(), e);
        }
    }

    public void sendMessage(Pair<String, ArrayList<Object>> order) {
        try {
            log.debug("%s Received order: %s", toString(), order.getLeft());
            switch (order.getLeft()) {
                case "keep-alive":
                    break;
                case "choke":
                    synchronized(this) {
                        amChoking = true;
                    }
                    choke();
                    break;
                case "unchoke":
                    synchronized(this) {
                        amChoking = false;
                    }
                    unchoke();
                    break;
                case "interested":
                    synchronized(this) {
                        amInterested = true;
                    }
                    interested();
                    break;
                case "not interested":
                    synchronized(this) {
                        amInterested = false;
                    }
                    uninterested();
                    break;
                case "have":
                    have(order.getRight());
                    break;
                case "bitfield":
                    bitfield();
                    break;
                case "request":
                    request(order.getRight());
                    break;
                case "piece":
                    piece(order.getRight());
                    break;
                case "cancel":
                    cancel(order.getRight());
                    break;
                case "port":
                    port();
                    break;
                default:
                    log.fatal("%s unexpected order: %s", toString(), order.getLeft());
                    shutdownSockets();
                    return;
            }
        } catch (IOException e) {
            log.fatal(e.getMessage(), e);
        }

    }
    
    /** 
     * Sends the bittorrent protocol handshake message to the peer.
     * @throws IOException
     */
    private void sendHandshake() throws IOException {
        log.debug("%s sending handshake", toString());
        byte[] message = new byte[68];
        message[0] = 19;
        byte[] pstr = "BitTorrent protocol".getBytes();
        for (int i = 0; i < pstr.length; i++) {
            message[1 + i] = pstr[i];
        }
        int reserved = 8;
        for (int i = 0; i < reserved; i++) {
            message[1 + pstr.length + i] = 0;
        }
        byte[] infoHash = peerManager.getInfoHash();
        for (int i = 0; i < infoHash.length; i++) {
            message[1 + pstr.length + reserved + i] = infoHash[i];
        }
        byte[] peerId = peerManager.getPeerId().getBytes();
        for (int i = 0; i < peerId.length; i++) {
            message[1 + pstr.length + reserved + infoHash.length + i] = peerId[i];
        }
        out.write(message);
        out.flush();
    }

    
    /** 
     * Waits for and receives a bittorrent protocol handshake message from
     * the peer. If it takes longer than 5 seconds to receive a handshake,
     * an IOException is thrown.
     * @throws IOException
     * @throws DataFormatException: If the handshake received did not conform to standards.
     * @throws InterruptedException: If the thread is interrupted while waiting 1
     */
    private void receiveHandshake()
            throws IOException, DataFormatException, InterruptedException{
        //Handle EOF
        log.debug("%s waiting for handshake", toString());
        Instant startTime = Instant.now();
        while (true) {
            if (!Instant.now().isBefore(startTime.plusSeconds(5))) {
                throw new IOException(String.format("%s timeout peerInfofor handshake.", toString()));
            }
            if (in.available() > 0) {
                break;
            }
            Thread.sleep(50);
        }
        int pstrlen = in.read();
        if (pstrlen == -1) {
            throw new IOException("EOF was reached");
        } else if (pstrlen != 19) {
            log.debug("%s received pstrlen is not 19", toString());
            throw new DataFormatException("pstrlen is " + pstrlen);
        }
        StringBuilder pstr = new StringBuilder();
        for (int i = 0; i < pstrlen; i++) {
            int c = in.read();
            if (c == -1) {
                throw new IOException("EOF was reached");
            }
            pstr.append((char) c);
        }
        if (!pstr.toString().equals("BitTorrent protocol")) {
            log.debug("%s received pst is not as expected", this);
            throw new DataFormatException("pstr is " + pstr);
        }
        byte[] reserved = new byte[8];
        for (int i = 0; i < reserved.length; i++) {
            int val = in.read();
            if (val == -1) {
                throw new IOException("EOF was reached");
            }
            reserved[i] = (byte) val;
        }
        parseReserved(reserved);
        byte[] infoHash = new byte[20];
        for (int i = 0; i < infoHash.length; i++) {
            int val = in.read();
            if (val == -1) {
                throw new IOException("EOF was reached");
            }
            infoHash[i] = (byte) val;
        }
        if (foundByPeerServer) {
            this.infoHash = infoHash;
        } else {
            if (!Arrays.equals(this.infoHash, infoHash)) {
                throw new SecurityException("InfoHash not matching");
            }
        }
        for (int i = 0; i < 20; i++) {
            if (in.read() == -1) {
                throw new IOException("EOF was reached");
            }
        }
        log.debug("%s received handshake", toString());
    }
    
    /** 
     * Parses the reserved bytes received from the handshake and
     * determines what protocol extensions are followed.
     * @param reserved
     */
    private void parseReserved(byte[] reserved) {
        if ((reserved[5] & 0x10) == 0x10) {
            log.debug("%s LTEP enabled", toString());
            LTEP = true;
        }
        if ((reserved[7] & 0x01) == 0x01) {
            log.debug("%s DHT enabled", toString());
            DHT = true;
        }
    }
    
    /**
     * Sends a choke message to the peer.
     * choke: <len=0001><id=0>
     * @throws IOException
     */
    private void choke() throws IOException {
        send((byte) 0);
    }
    
    /** 
     * Sends an unchoke message to the peer.
     * unchoke: <len=0001><id=1>
     * @throws IOException
     */
    private void unchoke() throws IOException {
        send((byte) 1);
    }
    
    /** 
     * Sends an interested message to the peer.
     * interested: <len=0001><id=2>
     * @throws IOException
     */
    private void interested() throws IOException {
        send((byte) 2);
    }
    
    /** 
     * Sends an uninterested message to the peer.
     * not interested: <len=0001><id=3>
     * @throws IOException
     */
    private void uninterested() throws IOException {
        send((byte) 3);
    }

    /** 
     * Sends a have message to the peer.
     * have: <len=0005><id=4><piece index>
     * @param args
     * @throws IOException
     */
    private void have(ArrayList<Object> args) throws IOException {
        int idx = (int) args.get(0);
        byte[] payload = intToUInt32(idx);
        send((byte) 4, payload);
    }
    
    /** 
     * Sends a bitfield message to the peer.
     * bitfield: <len=0001+X><id=5><bitfield>
     * @throws IOException
     */
    private void bitfield() throws IOException {
        byte[] bitfield = peerManager.getBitfield();
        send((byte) 5, bitfield);
    }
    
    /** 
     * Sends a request message to the peer.
     * request: <len=0013><id=6><index><begin><length>
     * @param args
     * @throws IOException
     */
    private void request(ArrayList<Object> args) throws IOException {
        int idx = (int) args.get(0);
        int begin = (int) args.get(1);
        int length = (int) args.get(2);
        byte[] payload = new byte[12];
        byte[] idxUint32 = intToUInt32(idx);
        byte[] beginUint32 = intToUInt32(begin);
        byte[] lengthUint32 = intToUInt32(length);
        for (int i = 0; i < 4; i++) {
            //Each uint is 4 bytes long
            payload[i] = idxUint32[i];
            payload[i + 4] = beginUint32[i];
            payload[i + 8] = lengthUint32[i];
        }
        send((byte) 6, payload);
    }

    /** 
     * Sends a piece message to the peer.
     * piece: <len=0009+X><id=7><index><begin><block>
     * @param args
     * @throws IOException
     */
    private void piece(ArrayList<Object> args) throws IOException {
        Request req = (Request) args.get(0);
        int idx = req.index;
        int begin = req.begin;
        byte[] block = req.block;
        //Check if the piece was cancelled
        for (int[] cancelInfo: cancelList) {
            if (cancelInfo[0] == idx && cancelInfo[1] == begin && cancelInfo[2] == block.length) {
                cancelList.remove(cancelInfo);
                return;
            }
        }
        byte[] payload = new byte[8 + block.length];
        byte[] idxUint32 = intToUInt32(idx);
        byte[] beginUint32 = intToUInt32(begin);
        for (int i = 0; i < 4; i++) {
            payload[i] = idxUint32[i];
            payload[i + 4] = beginUint32[i];
        }
        for (int i = 0; i < block.length; i++) {
            payload[i + 8] = block[i];
        }
        send((byte) 7, payload);
    }

    /** 
     * Sends a cancel message to the peer.
     * cancel: <len=0013><id=8><index><begin><length>
     * @param args
     * @throws IOException
     */
    private void cancel(ArrayList<Object> args) throws IOException {
        int idx = (int) args.get(0);
        int begin = (int) args.get(1);
        int length = (int) args.get(2);
        byte[] payload = new byte[12];
        byte[] idxUint32 = intToUInt32(idx);
        byte[] beginUint32 = intToUInt32(begin);
        byte[] lengthUint32 = intToUInt32(length);
        for (int i = 0; i < 4; i++) {
            //Each uint is 4 bytes long
            payload[i] = idxUint32[i];
            payload[i + 4] = beginUint32[i];
            payload[i + 8] = lengthUint32[i];
        }
        send((byte) 8, payload);
    }

    /** 
     * Sends a port message.
     * port: <len=0003><id=9><listen-port>
     * @throws IOException
     */
    private void port() throws IOException {
        log.warn("%s port is not implemented.", toString());
    }

    /** 
     * Sends a message with no payload.
     * @param id: id of the message according to the protocol.
     * @throws IOException
     */
    private void send(byte id) throws IOException {
        byte[] message = new byte[5];
        message[0] = 0;
        message[1] = 0;
        message[2] = 0;
        message[3] = 1;
        message[4] = id;
        out.write(message);
        out.flush();
    }

    /** 
     * Sends a message with a payload.
     * @param id: id of the message according to the protocol.
     * @param payload: the payload to be sent along with the message.
     * @throws IOException
     */
    private void send(byte id, byte[] payload) throws IOException {
        byte[] message = new byte[4 + 1 + payload.length];
        int length = 1 + payload.length;
        byte[] uint32 = intToUInt32(length);
        for (int i = 0; i < 4; i++) {
            message[i] = uint32[i];
        }
        message[4] = id;
        for (int i = 0; i < payload.length; i++) {
            message[5 + i] = payload[i];
        }
        out.write(message);
        out.flush();
    }

    /** 
     * Method for converting a java integer to a 4 byte
     * array representing a 32 big-endian int.
     * @param num
     * @return byte[]
     */
    private static byte[] intToUInt32(long num) {
        if (num > 4294967295L) {
            throw new InvalidParameterException("The supplied long is larger than 2^32-1");
        }
        byte[] out = new byte[4];
        for (int i = 0; i < 4; i++) {
            int byteCount = (int) (num/Math.pow(256, 3 - i));
            num -= byteCount*Math.pow(256, 3 - i);
            out[i] = (byte) byteCount;
        }
        return out;
    }

    /** 
     * Receives a have message from the peer and adds it to the haveQueue.
     * It also updates the peer's bitfield value.
     * have: <len=0005><id=4><piece index>
     * @throws IOException
     */
    private void receiveHave() throws IOException {
        int idx = 0;
        for (int i = 3; i >= 0; i--) {
            int val = in.read();
            if (val == -1) {
                throw new IOException("EOF was reached");
            }
            idx += val * Math.pow(256, i);
        }
        int bitfieldIndex = (int) (idx/8);
        int bitIndex = idx % 8;
        synchronized(this) {
            peerBitfield[bitfieldIndex] |= 128 >> bitIndex;
        }
        haveQueue.add(idx);
    }

    /** 
     * Receives peer's bitfield. Checks whether it is the first message after
     * the handshake and checks whether it conforms with the torrent's spec.
     * bitfield: <len=0001+X><id=5><bitfield>
     * @param length
     * @throws IOException
     * @throws SecurityException: If the bitfield size is not what is expected.
     */
    private void receiveBitfield(int length) throws IOException, SecurityException {
        if (receivedFirstMessage) { //Do I need to synchronize this?
            throw new SecurityException("The bitfield message is not the first message after the hanshake");
        }
        synchronized(this) {
            receivedFirstMessage = true;
            receivedBitfield = true;
            if (length != peerBitfield.length) {
                log.warn("%s received bitfield with size %d; expected %d", toString(), length, peerBitfield.length);
                throw new SecurityException("Peer bitfield does not match the expected size");
            }
            for (int i = 0; i < length; i++) {
                int val = in.read();
                if (val == -1) {
                    throw new IOException("EOF was reached");
                }
                peerBitfield[i] = (byte) val;
            }
        }
    }
    
    /** 
     * Receives a request from a peer and adds it to the requestQueue.
     * request: <len=0013><id=6><index><begin><length>
     * @throws IOException
     */
    private void receiveRequest() throws IOException {
        int idx = 0;
        for (int i = 3; i >= 0; i--) {
            int val = in.read();
            if (val == -1) {
                throw new IOException("EOF was reached");
            }
            idx += val * Math.pow(256, i);
        }
        int begin = 0;
        for (int i = 3; i >= 0; i--) {
            int val = in.read();
            if (val == -1) {
                throw new IOException("EOF was reached");
            }
            begin += val * Math.pow(256, i);
        }
        int length = 0;
        for (int i = 3; i >= 0; i--) {
            int val = in.read();
            if (val == -1) {
                throw new IOException("EOF was reached");
            }
            length += val * Math.pow(256, i);
        }
        if (length > Math.pow(2, 15)) {
            log.debug("%s the requested piece size was too big; request dropped", toString());
            return;
        }
        requestQueue.add(new Request(idx, begin, length));
    }
    
    /** 
     * Receives a piece from the peer, adds it to the pieceQueue
     * and then reduces the requestCount by 1.
     * piece: <len=0009+X><id=7><index><begin><block>
     * @param length
     * @throws IOException
     */
    private void receivePiece(int length) throws IOException {
        int idx = 0;
        for (int i = 3; i >= 0; i--) {
            int val = in.read();
            if (val == -1) {
                throw new IOException("EOF was reached");
            }
            idx += val * Math.pow(256, i);
        }
        int begin = 0;
        for (int i = 3; i >= 0; i--) {
            int val = in.read();
            if (val == -1) {
                throw new IOException("EOF was reached");
            }
            begin += val * Math.pow(256, i);
        }
        byte[] block = new byte[length - 8];
        for (int i = 0; i < length - 8; i++) {
            int val = in.read();
            if (val == -1) {
                throw new IOException("EOF was reached");
            }
            block[i] = (byte) val;
        }
        Request piece = new Request(idx, begin, block);
        pieceQueue.add(piece);
        synchronized(this) {
            if (requestCount > 0) {
                requestCount -= 1;
            }
        }
    }

    /** 
     * Receives a cancel message from the peer and adds it to the
     * cancelList. The peer class checks the cancelList everytime
     * before sending a piece in order to not send a cancelled one.
     * cancel: <len=0013><id=8><index><begin><length>
     * @throws IOException
     */
    private void receiveCancel() throws IOException {
        int idx = 0;
        for (int i = 3; i >= 0; i--) {
            int val = in.read();
            if (val == -1) {
                throw new IOException("EOF was reached");
            }
            idx += val * Math.pow(256, i);
        }
        int begin = 0;
        for (int i = 3; i >= 0; i--) {
            int val = in.read();
            if (val == -1) {
                throw new IOException("EOF was reached");
            }
            begin += val * Math.pow(256, i);
        }
        int length = 0;
        for (int i = 3; i >= 0; i--) {
            int val = in.read();
            if (val == -1) {
                throw new IOException("EOF was reached");
            }
            length += val * Math.pow(256, i);
        }
        int[] arr = {idx, begin, length};
        cancelList.add(arr);
    }

    /** 
     * Receives a port message from the peer that is used
     * for the DHT extension.
     * port: <len=0003><id=9><listen-port>
     * @throws IOException
     */
    private void receivePort() throws IOException {
        for (int i = 0; i < 2; i++) {
            if (in.read() == -1) {
                throw new IOException("EOF was reached");
            }
        }
    }
    
    /** 
     * Receives a bittorrent extension message and then deals
     * with it based on the extendedId.
     * @param length
     * @throws IOException
     */
    private void receiveExtension(int length) throws IOException {
        for (int i = 0; i < length; i++) {
            if (in.read() == -1) {
                throw new IOException("EOF was reached");
            }
        }
        //byte extendedId = in.read();
        byte extendedId = 0;
        if (extendedId == 0) {
            //Implement the extension hanshake
        } else {
            //Implement other extensions
        }
    }

    public synchronized byte[] getPeerBitfield() {
        return peerBitfield;
    }
    
    /** 
     * @return boolean indicating whether I am chocking the peer.
     */
    public synchronized boolean getAmChocking() {
        return amChoking;
    }
    
    /** 
     * @return boolean indicating whether I am interested in the peer.
     */
    public synchronized boolean getAmInterested() {
        return amInterested;
    }
    
    /** 
     * @return boolean indicating whether the peer is chocking me.
     */
    public synchronized boolean getPeerChocking() {
        return peerChocking;
    }
    
    /** 
     * @return boolean indicating whether the peer is interested in me.
     */
    public synchronized boolean getPeerInterested() {
        return peerInterested;
    }

    public synchronized boolean hasReceivedFirstMessage() {
        return receivedFirstMessage;
    }

    public synchronized boolean hasReceivedBitfield() {
        return receivedBitfield;
    }
    
    /** 
     * @return InetAddress of the peer.
     */
    public InetAddress getIp() {
        return ip;
    }
    
    /** 
     * @return int of the peer.
     */
    public int getPort() {
        return port;
    }
    
    /** 
     * @return Request from the end of the requestQueue
     */
    public Request getRequest() {
        return requestQueue.poll();
    }

    /**
     * @return Integer indicating the index of the piece which the peer has.
     */
    public Integer getPeerHaves() {
        return haveQueue.poll();
    }
    
    /** 
     * @return Request object of a piece from the end of the pieceQueue.
     */
    public Request getNewPiece() {
        return pieceQueue.poll();
    }

    /** 
     * @return byte[] infoHash of the peer.
     */
    public byte[] getInfoHash() {
        return infoHash;
    }

    /** 
     * @return Pair<InetAddress, Integer>
     */
    public Pair<InetAddress, Integer> getNetworkPair() {
        return new Pair<InetAddress, Integer>(ip, port);
    }

    /** 
     * @return in stating the number of outstanding requests.
     */
    public synchronized int getRequestCount() {
        return requestCount;
    }

    /**
     * Increases the number of outstanding requests by 1.
     */
    public synchronized void incRequestCount() {
        requestCount += 1;
    }

    public synchronized void resetRequestCount() {
        requestCount = 0;
    }

    /**
     * Graciously shuts down the peer.
     */
    public synchronized void close() {
        keepRunning = false;
        shutdownSockets();
    }

    /**
     * Closes all open files. Should be used only if the
     * thread has not yet been started.
     */
    public void shutdownSockets() {
        try {
            in.close();
            out.close();
            sock.close();
        } catch (IOException e) {
            log.warn("%s could not close the sockets.", toString());
        }
    }

    /**
     * @return boolean indicating whether the peer supports LTEP.
     */
    public boolean getLTEP() {
        return LTEP;
    }

    /**
     * @return boolean indicating whether the peer supports DHT.
     */
    public boolean getDHT() {
        return DHT;
    }
    
    /** 
     * @return int
     */
    @Override
    public int hashCode() {
        return ip.hashCode() ^ Integer.hashCode(port);
    }
    
    /** 
     * An object equals a peer if and only if that peer is an
     * instance of Peer and their IP addresses are equal and
     * their ports are equal.
     * @param obj
     * @return boolean
     */
    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof Peer)) {
            return false;
        }
        Peer peer = (Peer) obj;
        return this.ip.equals(peer.getIp()) && this.port == peer.getPort();
    }
    
    /** 
     * @return String
     */
    @Override
    public String toString() {
        return String.format("Peer[name=%s, ip=%s, port=%s]", peerManager.getFileName(), ip.toString(), String.valueOf(port));
    }
}