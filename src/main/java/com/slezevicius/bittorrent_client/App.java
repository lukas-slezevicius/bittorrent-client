package com.slezevicius.bittorrent_client;

import java.io.UnsupportedEncodingException;

/**
 * Hello world!
 *
 */
public class App 
{
    public static void main( String[] args )
    {
        /*
        String input = "d2:abi12534e4:hahai-12e3:MYRli1ei2ed2:jji59eeee";
        Bencoding b = new Bencoding(input);
        Object out = b.decode();
        System.out.println(out);
        */
        byte[] cont = Tracker.sendRequest("https://api.covid19api.com/country/spain/status/confirmed");
        try {
            System.out.println(new String(cont, "US-ASCII"));
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
    }
}