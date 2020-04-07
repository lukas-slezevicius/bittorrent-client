package com.slezevicius.bittorrent_client;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.DataFormatException;

public class Peer extends Thread { 
    private Torrent torrent;
    private PeerManager peerManager;
    private volatile ConcurrentLinkedQueue<Pair<String, ArrayList<Object>>> orderQueue;
    private volatile ConcurrentLinkedQueue<ArrayList<Object>> pieceQueue;
    private InetAddress ip;
    private int port;
    private Socket sock;
    private DataOutputStream out;
    private DataInputStream in;
    private volatile boolean amChoking = true;
    private volatile boolean amInterested = false;
    private volatile boolean peerChocking = true;
    private volatile boolean peerInterested = false;
    private volatile byte[] peerBitfield;
    private volatile boolean keepRunning = true;
    private boolean LTEP = false;
    private boolean DHT = false;
    private Logger log;

    Peer(Pair<InetAddress, Integer> pair, Torrent torrent, PeerManager peerManager) {
        log = Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);
        log.setLevel(Level.ALL);
        this.torrent = torrent;
        this.peerManager = peerManager;
        peerBitfield = new byte[torrent.getBitfieldLength()];
        orderQueue = new ConcurrentLinkedQueue<>();
        pieceQueue = peerManager.getPieceQueue();
        ip = pair.getLeft();
        port = pair.getRight();
    }

    Peer(Socket sock, PeerManager peerManager)
            throws IOException, DataFormatException, InterruptedException, SecurityException {
        log = Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);
        log.setLevel(Level.ALL);
        this.peerManager = peerManager;
        ip = sock.getInetAddress();
        port = sock.getPort();
        receiveHandshake();
        sendHandshake();
    }

    @Override
    public void run() {
        try {
            peerManager.addPeer(this);
            sock = new Socket(ip, port);
            out = new DataOutputStream(sock.getOutputStream());
            in = new DataInputStream(sock.getInputStream());
            sendHandshake();
            receiveHandshake();
            while (true) {
                synchronized(this) {
                    if (!keepRunning) {
                        sock.close();
                        return;
                    }
                }
                if (in.available() > 0) {
                    readMessage();
                }
                Pair<String, ArrayList<Object>> order = orderQueue.poll();
                if (order == null) {
                    continue;
                }
                switch (order.getLeft()) {
                    case "keep-alive":
                        //Keep alive
                        break;
                    case "choke":
                        choke();
                        break;
                    case "unchoke":
                        unchoke();
                        break;
                    case "interested":
                        interested();
                        break;
                    case "not interested":
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
                        log.warning(String.format("%s unexpected order: %s", this.toString(), order.getLeft()));
                        return;
                }
            }
        } catch (IOException e) {
            log.warning(String.format("%s IOException"));
            log.log(Level.WARNING, e.getMessage(), e);
        } catch (InterruptedException e) {
            log.warning(String.format("%s got interrupted", this.toString()));
            log.log(Level.WARNING, e.getMessage(), e);
        } catch (SecurityException e) {
            log.log(Level.WARNING, e.getMessage(), e);
        } catch (DataFormatException e) {
            log.log(Level.WARNING, e.getMessage(), e);
        }
    }

    private void readMessage() throws IOException {
        int length = 0;
        for (int i = 3; i >= 0; i--) {
            length += (in.readByte() & 0xFF) * Math.pow(256, i);
        }
        if (length == 0) {
            //Keep alive
            return;
        }
        int payloadLength = length - 1;
        int id = in.readByte() & 0xFF;
        switch (id) {
            case 0:
                peerChocking = true;
                break;
            case 1:
                peerChocking = false;
                break;
            case 2:
                peerInterested = true;
                break;
            case 3:
                peerInterested = false;
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
                log.warning(String.format("%s unknown message id %d", this.toString(), id));
                in.skipBytes(payloadLength);
        }
    }

    private void sendHandshake() throws IOException {
        byte[] message = new byte[68];
        message[0] = 19;
        byte[] pstr = "BitTorrent protocol".getBytes();
        for (int i = 0; i < pstr.length; i++) {
            message[1 + i] = pstr[i];
        }
        int reserved = 8;
        for (int i = 0; i < reserved; i++) {
            message[1 + pstr.length + i] = '0';
        }
        byte[] infoHash = torrent.getInfoHash();
        for (int i = 0; i < infoHash.length; i++) {
            message[1 + pstr.length + reserved + i] = infoHash[i];
        }
        byte[] peerId = torrent.getPeerId().getBytes();
        for (int i = 0; i < peerId.length; i++) {
            message[1 + pstr.length + reserved + infoHash.length + i] = peerId[i];
        }
        out.write(message);
        out.flush();
    }

    private void receiveHandshake()
            throws IOException, DataFormatException, InterruptedException, SecurityException {
        Instant startTime = Clock.systemUTC().instant();
        while (true) {
            if (!Clock.systemUTC().instant().isBefore(startTime.plusSeconds(5))) {
                throw new IOException(String.format("%s timeout for handshake.", this.toString()));
            }
            if (in.available() > 0) {
                break;
            }
            Thread.sleep(50);
        }
        byte pstrlen = in.readByte();
        if (pstrlen != 19) {
            log.warning(String.format("%s received pstrlen is not 19", this.toString()));
            throw new DataFormatException("pstrlen is " + pstrlen);
        }
        StringBuilder pstr = new StringBuilder();
        for (int i = 0; i < pstrlen; i++) {
            pstr.append((char) in.readByte());
        }
        if (!pstr.toString().equals("BitTorrent protocol")) {
            log.warning(String.format("%s received pst is not as expected", this.toString()));
            throw new DataFormatException("pstr is " + pstr.toString());
        }
        byte[] reserved = new byte[8];
        for (int i = 0; i < reserved.length; i++) {
            reserved[i] = in.readByte();
        }
        parseReserved(reserved);
        byte[] infoHash = new byte[20];
        for (int i = 0; i < infoHash.length; i++) {
            infoHash[i] = in.readByte();
        }
        if (torrent != null) {
            if (!Arrays.equals(infoHash, torrent.getInfoHash())) {
                log.warning(String.format("%s info hash does not match"));
                throw new SecurityException("Received info hash " + Metainfo.bytesToHex(infoHash));
            }
        } else {
            torrent = peerManager.getTorrent(infoHash);
            if (torrent == null) {
                throw new SecurityException("Received an unrecognized info hash");
            }
        }
        byte[] peerId = new byte[20];
        for (int i = 0; i < peerId.length; i++) {
            peerId[i] = in.readByte();
        }
        //Potential threat if peerId does not match the one given by the tracker
    }

    private void parseReserved(byte[] reserved) {
        if ((reserved[5] & 0x10) == 0x10) {
            LTEP = true;
        }
        if ((reserved[7] & 0x01) == 0x01) {
            DHT = true;
        }
    }

    private void choke() throws IOException {
        send((byte) 0);
    }

    private void unchoke() throws IOException {
        send((byte) 1);
    }

    private void interested() throws IOException {
        send((byte) 2);
    }

    private void uninterested() throws IOException {
        send((byte) 3);
    }

    private void have(ArrayList<Object> args) throws IOException {
        int idx = (int) args.get(0);
        byte[] payload = intToUInt32(idx);
        send((byte) 6, payload);
    }

    private void bitfield() throws IOException {
        byte[] bitfield = peerManager.getBitfield();
        send((byte) 5, bitfield);
    }

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

    private void piece(ArrayList<Object> args) throws IOException {
        int idx = (int) args.get(0);
        int begin = (int) args.get(1);
        byte[] block = (byte[]) args.get(2);
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

    private void port() throws IOException {
        throw new RuntimeException("Not implemented");
    }

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

    private static byte[] intToUInt32(int num) {
        byte[] out = new byte[4];
        for (int i = 0; i < 4; i++) {
            int byteCount = (int) (num/Math.pow(256, 3 - i));
            num -= byteCount*Math.pow(256, 3 - i);
            out[i] = (byte) byteCount;
        }
        return out;
    }

    private void receiveHave() throws IOException {
        int idx = 0;
        for (int i = 3; i >= 0; i--) {
            idx += (in.readByte() & 0xFF) * Math.pow(256, i);
        }
        int B = (int) (idx/8);
        int b = idx % 8;
        peerBitfield[B] |= (byte) ((int) Math.pow(2, b));
    }

    private void receiveBitfield(int length) throws IOException, SecurityException {
        if (length != peerBitfield.length) {
            throw new SecurityException("Peer bitfield does not match the expected size");
        }
        for (int i = 0; i < length; i++) {
            peerBitfield[i] = in.readByte();
        }
    }

    private void receiveRequest() throws IOException {
        int idx = 0;
        for (int i = 3; i >= 0; i--) {
            idx += (in.readByte() & 0xFF) * Math.pow(256, i);
        }
        int begin = 0;
        for (int i = 3; i >= 0; i--) {
            begin += (in.readByte() & 0xFF) * Math.pow(256, i);
        }
        int length = 0;
        for (int i = 3; i >= 0; i--) {
            length += (in.readByte() & 0xFF) * Math.pow(256, i);
        }
        peerManager.receivedRequest(this, idx, begin, length);
    }

    private void receivePiece(int length) throws IOException {
        int idx = 0;
        for (int i = 3; i >= 0; i--) {
            idx += (in.readByte() & 0xFF) * Math.pow(256, i);
        }
        int begin = 0;
        for (int i = 3; i >= 0; i--) {
            begin += (in.readByte() & 0xFF) * Math.pow(256, i);
        }
        byte[] block = new byte[length - 8];
        for (int i = 0; i < length - 8; i++) {
            block[i] = in.readByte();
        }
        ArrayList<Object> piece = new ArrayList<>();
        piece.add(idx);
        piece.add(begin);
        piece.add(block);
        pieceQueue.add(piece);
    }

    private void receiveCancel() throws IOException {
        int idx = 0;
        for (int i = 3; i >= 0; i--) {
            idx += (in.readByte() & 0xFF) * Math.pow(256, i);
        }
        int begin = 0;
        for (int i = 3; i >= 0; i--) {
            begin += (in.readByte() & 0xFF) * Math.pow(256, i);
        }
        int length = 0;
        for (int i = 3; i >= 0; i--) {
            length += (in.readByte() & 0xFF) * Math.pow(256, i);
        }
        peerManager.receivedCancel(this, idx, begin, length);
    }

    private void receivePort() throws IOException {
        byte b1 = in.readByte();
        byte b2 = in.readByte();
        int port = (b1 & 0xFF)*265 + (b2 & 0xFF);
    }

    private void receiveExtension(int length) throws IOException {
        for (int i = 0; i < length; i++) {
            in.readByte(); //Temporary ignore
        }
        //byte extendedId = in.readByte();
        byte extendedId = 0;
        if (extendedId == 0) {
            //Implement the extension hanshake
        } else {
            //Implement other extensions
        }
    }

    public boolean getAmChocking() {
        return amChoking;
    }

    public boolean getAmInterested() {
        return amInterested;
    }

    public boolean getPeerChocking() {
        return peerChocking;
    }

    public boolean getPeerInterested() {
        return peerInterested;
    }

    public InetAddress getIp() {
        return ip;
    }

    public int getPort() {
        return port;
    }

    public ConcurrentLinkedQueue<Pair<String, ArrayList<Object>>> getOrderQueue() {
        return orderQueue;
    } 

    public synchronized void stopRunning() {
        keepRunning = false;
    }

    @Override
    public int hashCode() {
        return ip.hashCode() ^ Integer.hashCode(port);
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof Peer)) {
            return false;
        }
        Peer peer = (Peer) obj;
        return this.ip.equals(peer.getIp()) && this.port == peer.getPort();
    }

    @Override
    public String toString() {
        return String.format("Peer[ip=%s, port=%s]", ip.toString(), String.valueOf(port));
    }
}