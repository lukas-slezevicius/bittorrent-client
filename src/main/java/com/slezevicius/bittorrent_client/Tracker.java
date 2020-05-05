package com.slezevicius.bittorrent_client;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.DataFormatException;

import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

/**
 * Tracker object that deals with the bittorrent tracker for
 * a particular torrent file. Keeps updating the tracker
 * with the required interval time. Allows other classes
 * to get updates on the current known peers.
 */
public class Tracker extends Thread {
    private Metainfo metainfo;
    private Torrent torrent;
    private long interval;
    private String trackerId;
    private long complete;
    private long incomplete;
    private volatile List<Pair<InetAddress, Integer>> peers;
    private volatile boolean receivedNewPeers = false;
    private volatile boolean keepRunning = true;
    private Logger log;

    Tracker(Metainfo metainfo, Torrent torrent) throws URISyntaxException, DataFormatException {
        log  = Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);
        log.setLevel(Level.ALL);
        this.metainfo = metainfo;
        this.torrent = torrent;
        log.finest(String.format("%s initialized", this.toString()));
    }

    /**
     * The thread's main loop for the tracker. Keeps sending
     * update tracker messages at a specified interval.
     */
    @Override
    public void run() {
        try {
            send("started");
            synchronized(this) {
                receivedNewPeers = true;
            }
            log.finest(String.format("%s sent started", this.toString()));
            log.finest("Gonna sleep for " + interval);
            Thread.sleep(interval * 1000);
            while (keepRunning) {
                send("");
                synchronized(this) {
                    receivedNewPeers = true;
                }
                Thread.sleep(interval * 1000);
                log.finest(String.format("%s sent update"));
            }
        } catch (IOException e) {
            log.log(Level.WARNING, e.getMessage(), e);
        } catch (URISyntaxException e) {
            log.log(Level.WARNING, e.getMessage(), e);
        } catch (DataFormatException e) {
            log.log(Level.WARNING, e.getMessage(), e);
        } catch (InterruptedException e) {
            if (keepRunning) {
                log.warning(String.format("%s: The thread was interrupted", this.toString()));
                return;
            }
        }
        try {
            if (torrent.isComplete()) {
                send("completed"); //Make sure that this receives a response
                log.finest(String.format("%s sent completed", this.toString()));
            } else {
                send("stopped");
                log.finest(String.format("%s sent stopped", this.toString()));
            }
        } catch (Exception e) {
            log.warning(String.format("%s: Could not send the final message to the tracker", this.toString()));
            log.log(Level.WARNING, e.getMessage(), e);
        }
    }
    
    /** 
     * Sends a message to the tracker with the given event.
     * @param event: the event parameter to send.
     * @throws URISyntaxException
     * @throws DataFormatException
     * @throws IOException
     */
    private void send(String event) throws URISyntaxException, DataFormatException, IOException {
        String trackerURI = buildTrackerURL(event);
        byte[] responseContent = sendRequest(trackerURI);
        Bencoding b = new Bencoding(responseContent);
        Object responseObject = b.decode();
        updateFields(responseObject);
    }
    
    /** 
     * Updates the tracker fields from the decoded tracker response.
     * @param responseObject
     * @throws DataFormatException: If the response did not follow the tracker protocol properly.
     */
    private void updateFields(Object responseObject) throws DataFormatException {
        if (responseObject instanceof LinkedHashMap) {
            LinkedHashMap<String, Object> responseDict = (LinkedHashMap<String, Object>) responseObject;
            if (responseDict.containsKey("failure reason")) {
                if (responseDict.get("failure reason") instanceof byte[]) {
                    String failure = new String((byte[]) responseDict.get("failure reason"));
                    throw new DataFormatException(String.format("%s failure: %s", this.toString(), failure));
                } else {
                    throw new DataFormatException(String.format("%s failure reason type is not byte string", this.toString()));
                }
            }
            if (responseDict.containsKey("warning message")) {
                if (responseDict.get("warning message") instanceof byte[]) {
                    String warning = new String((byte[]) responseDict.get("warning message"));
                    throw new DataFormatException(String.format("%s warning: %s", this.toString(), warning));
                } else {
                    log.warning(String.format("%s: warning message type is not byte string"));
                }
            }
            if (responseDict.containsKey("interval")) {
                if (responseDict.get("interval") instanceof Long) {
                    interval = (long) responseDict.get("interval");
                } else {
                    throw new DataFormatException(String.format("%s interval value is not of type int", this.toString()));
                }
            } else {
                throw new DataFormatException(String.format("%s interval key not in the dict", this.toString()));
            }
            if (responseDict.containsKey("complete")) {
                if (responseDict.get("complete") instanceof Long) {
                    complete = (long) responseDict.get("complete");
                } else {
                    throw new DataFormatException(String.format("%s complete value is not of type int", this.toString()));
                }
            } else {
                throw new DataFormatException(String.format("%s complete key not in the dict", this.toString()));
            }
            if (responseDict.containsKey("incomplete")) {
                if (responseDict.get("incomplete") instanceof Long) {
                    incomplete = (long) responseDict.get("incomplete");
                } else {
                    throw new DataFormatException(String.format("%s incomplete value is not of type int", this.toString()));
                }
            } else {
                throw new DataFormatException(String.format("%s incomplete key not in the dict", this.toString()));
            }
            if (responseDict.containsKey("peers")) {
                if (responseDict.get("peers") instanceof ArrayList) {
                    //List model of peers
                    synchronized(this) {
                        updatePeers((ArrayList<Object>) responseDict.get("peers"));
                    }
                } else if (responseDict.get("peers") instanceof byte[]) {
                    //Compact model of peers
                    synchronized(this) {
                        updatePeers((byte[]) responseDict.get("peers"));
                    }
                } else {
                    throw new DataFormatException(String.format("%s peers is of an invalid type", this.toString()));
                }
            } else {
                throw new DataFormatException(String.format("%s peers key not in the dict", this.toString()));
            }
        } else {
            throw new DataFormatException(String.format("%s the response from the tracker must be a dictionary.", this.toString()));
        }
    }

    /** 
     * Updates the peer fields from the compact peer representation.
     * @param peerBytes
     * @throws DataFormatException
     */
    private void updatePeers(byte[] peerBytes) throws DataFormatException {
        if (peerBytes.length % 6 != 0) {
            throw new DataFormatException(String.format("%s invalid length of peer byte array", this.toString()));
        }
        peers = new ArrayList<>();
        int i = 0;
        while (i < peerBytes.length) {
            InetAddress ip;
            try {
                ip = InetAddress.getByAddress(Arrays.copyOfRange(peerBytes, i, i + 4));
            } catch (UnknownHostException e) {
                log.fine(String.format("%s unknown host", this.toString()));
                continue;
            }
            int port = (peerBytes[i + 4] & 0xFF)*256 + (peerBytes[i + 4 +1] & 0xFF);
            peers.add(new Pair<InetAddress, Integer>(ip, port));
            i += 6;
        }
    }
    
    /** 
     * Updates the peer fields from the dictionary peer representation.
     * @param peerList
     * @throws DataFormatException
     */
    private void updatePeers(ArrayList<Object> peerList) throws DataFormatException {
        peers = new ArrayList<>();
        for (Object obj : peerList) {
            if (obj instanceof HashMap) {
                HashMap<String, Object> dict = (HashMap<String, Object>) obj;
                InetAddress ip;
                int port;
                if (!dict.containsKey("peer id")) {
                    throw new DataFormatException(String.format("%s the dict in the peer list has to contain the key peer id", this.toString()));
                }
                if (dict.containsKey("ip")) {
                    if (dict.get("ip") instanceof byte[]) {
                        try {
                            ip = InetAddress.getByName(new String((byte[]) dict.get("ip")));
                        } catch (UnknownHostException e) {
                            log.fine(String.format("%s unknown host", this.toString()));
                            continue;
                        }
                    } else {
                        throw new DataFormatException(String.format("%s the provided ip address in peer list is not of type byte string", this.toString()));
                    }
                } else {
                    throw new DataFormatException(String.format("%s the dict in the peer list has to contain the key ip", this.toString()));
                }
                if (dict.containsKey("port")) {
                    if (dict.get("port") instanceof Long) {
                        port = (int) dict.get("port");
                    } else {
                        throw new DataFormatException(String.format("%s the provided port in peer list is not of type int", this.toString()));
                    }
                } else {
                    throw new DataFormatException(String.format("%s the dict in the peer list has to contain the key port", this.toString()));
                }
                peers.add(new Pair<InetAddress, Integer>(ip, port));
            } else {
                throw new DataFormatException(String.format("%s an element of the peer list is not of type dict", this.toString()));
            }
        }
    }
    
    /** 
     * Builds the required url with the needed parameters for requesting the
     * tracker.
     * @param event
     * @return String
     */
    private String buildTrackerURL(String event) {
        StringBuilder url = new StringBuilder(metainfo.getAnnounce());
        url.append("?info_hash=");
        url.append(percentEncode(metainfo.getInfoHash()));
        url.append("&peer_id=");
        url.append(torrent.getPeerId());
        url.append("&port=");
        url.append(String.valueOf(torrent.getPort()));
        url.append("&uploaded=");
        url.append(String.valueOf(torrent.getUploaded()));
        url.append("&downloaded=");
        url.append(String.valueOf(torrent.getDownloaded()));
        url.append("&left=");
        url.append(String.valueOf(metainfo.getLength() - torrent.getDownloaded()));
        url.append("&compact=1");
        url.append("&event=");
        url.append(event);
        if (trackerId != null) {
            url.append("&trackerid=");
            url.append(trackerId);
        }
        return new String(url);
    }

    /** 
     * Percent encodes the infoHash to be compliant with the url character rules.
     * @param in: infoHash to percent encode.
     * @return percent encoded String.
     */
    private static String percentEncode(byte[] in) {
        StringBuilder out = new StringBuilder(in.length * 2);
        for (int i = 0; i < in.length; i++) {
            if (isLetterOrDigit(in[i]) || isSpecialChar(in[i])) {
                out.append((char) in[i]);
            } else {
                out.append('%');
                out.append(Metainfo.bytesToHex(Arrays.copyOfRange(in, i, i + 1)));
            }
        }
        return new String(out);
    }
    
    /** 
     * @param c
     * @return boolean whether a character is a letter/digit
     */
    private static boolean isLetterOrDigit(byte c) {
        if ((c >= 97 && c <= 122) || (c >= 65 && c <= 90) || (c >= 48 && c <= 57)) {
            return true;
        }
        return false;
    }
    
    /** 
     * @param c
     * @return boolean whether a character is special (for url exceptions)
     */
    private static boolean isSpecialChar(byte c) {
        if (c == '_' || c == '-' || c == '.' || c == '~') {
            return true;
        }
        return false;
    }
    
    /** 
     * Sends the request to the given url.
     * @param url
     * @return byte[]
     * @throws IOException
     */
    private static byte[] sendRequest(String url) throws IOException {
        CloseableHttpClient httpclient = HttpClients.createDefault();
        HttpGet httpGet = new HttpGet(url);
        CloseableHttpResponse response = httpclient.execute(httpGet);
        HttpEntity entity = response.getEntity();
        InputStream stream = entity.getContent();
        byte[] content = stream.readAllBytes();
        stream.close();
        EntityUtils.consume(entity);
        return content;
    }
    
    /** 
     * @return long
     */
    public long getInterval() {
        return interval;
    }
    
    /** 
     * @return long
     */
    public long getComplete() {
        return complete;
    }

    /** 
     * @return long
     */
    public long getIncomplete() {
        return incomplete;
    }

    /**
     * @return boolean whether new peers have been received.
     */
    public synchronized boolean newPeers() {
        return receivedNewPeers;
    }

    /**
     * Sets the receivedNewPeers to false once the new peers have been taken.
     */
    public synchronized void takenNewPeers() {
        receivedNewPeers = false;
    }

    /**
     * @return List<Pair<InetAddress, Integer>>
     */
    public synchronized List<Pair<InetAddress, Integer>> getNewPeers() {
        return peers;
    }

    /**
     * Graciously shuts down the tracker.
     */
    public void shutdown() {
        keepRunning = false;
        this.interrupt();
    }
    
    /** 
     * @return String for printing.
     */
    @Override
    public String toString() {
        return String.format("Tracker [name=%s, url=%s]", metainfo.getName(), metainfo.getAnnounce());
    }
}