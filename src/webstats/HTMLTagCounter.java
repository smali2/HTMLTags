/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package webstats;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import javafx.util.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Bismillah
 * 
 * My first attempt in making a multi-thread Java app. This application 
 * prints different HTML tags along with counts found in all the pages within 
 * certain level deep.
 * 
 * @author Ali
 * 
 */
public class HTMLTagCounter implements Runnable {

    private static Logger s_logger = LoggerFactory.getLogger(HTMLTagCounter.class);
    
    // Crawling parameters
    private static int max_path = 3;
    private static int max_pages = 10;
 
    // Running list of anchors seen
    private static TreeSet<String> anchors = new TreeSet<>(); // idea of using TreeSet for sorted list was from http://stackoverflow.com/questions/8725387/why-is-there-no-sortedlist-in-java
    
    // A Hashmap to quickly insert counts of anchors inside. Key = hashCode of anchor and Value = Pair<Anchor, Count>
    private static ConcurrentHashMap<Long, Pair<String, Integer>> anchor_hm = new ConcurrentHashMap<>(30);
    
    // Web Page Visits
    private static ConcurrentLinkedQueue<Pair<String, Integer>> queue = new ConcurrentLinkedQueue();
    private static CopyOnWriteArrayList<Long> processing_urls = new CopyOnWriteArrayList(); // a list of URLs that are being crawled
    private static ConcurrentHashMap<Long, Pair<String, Integer>> seen_urls = new ConcurrentHashMap<>(24); // used to quickly determine if a URL is already visited. Pair refers to <URL, TagCount>
 
    // A dummy object used for locking purpose
    private final Object lock2 = new Object(); // used to synchronize updates on processing_urls and seen_urls
    private final Object lock1 = new Object(); // used to synchronize updates on processing_urls and seen_urls
    
    /**
     * New thread kick-off
     */
    public void run() {
        if (!queue.isEmpty()) {
            Pair<String, Integer> p = queue.poll(); // this will dequeue the URL from the list as well
            String candidate = p.getKey();
            Integer path = p.getValue();
            long candidate_hashcode = candidate.hashCode();
            if (!seen_urls.containsKey(candidate_hashcode) || !processing_urls.contains(candidate_hashcode)) {
                processing_urls.add(candidate_hashcode);
                s_logger.info("Now visiting URL: "+candidate);
                int tagCount = oneStep(candidate, path);
                if (tagCount!=0) { // Because if it is zero then probably the URL is either not an HTML page or has incorrect syntax to it even if it starts with http:
                    Pair<String, Integer> visited = new Pair(candidate, tagCount);
                    synchronized (lock1) {
                        seen_urls.put(candidate_hashcode, visited);
                        processing_urls.remove(candidate_hashcode);
                        if (seen_urls.size()==max_pages) {    
                           Thread.currentThread().interrupt();
                        }       
                    }
                }       
            }
        }
    }
            
    /**
     * Helper function that takes an input URL, path level, and counts
     * number of HTML tags in the URL
     * 
     * @param string_url
     * @param path
     * @return 
     */
    private int oneStep(String string_url, int path) {
        URL url;
        InputStream is = null;
        BufferedReader br;
        String html;
        int tag_counts = 0;
        int a_flag = 0; // if an anchor tag is detected
        
        try {
            // Code for opening URL etc. Idea taken from http://stackoverflow.com/questions/238547/how-do-you-programmatically-download-a-webpage-in-java
            url = new URL(string_url);
            try {
                is = url.openStream();  // throws an IOException
                br = new BufferedReader(new InputStreamReader(is));
            } catch (FileNotFoundException fnf) {
                return 0;
            } catch (UnknownHostException uhe) {
                return 0;
            } catch (IOException ioe) {
                return 0;
            }
            
            // Code for constructing HTML code
            StringBuilder sb = new StringBuilder();
            String line;
            int i = 0;
            while ((line = br.readLine()) != null) {
                sb.append(line);                
            }
            html = sb.toString().toLowerCase();
            
            // Search for tags
            while (i < html.length()) {
                if (html.charAt(i)=='<') {
                    try {
                        if (html.charAt(i+1)!='/' && html.charAt(i+1)!='!') { // not a closing tag nor a comment tag
                            int j = i;
                            // String for detected tag
                            StringBuilder tag_sb = new StringBuilder();
                            
                            while (html.charAt(j)!='>') {
                                if (html.charAt(j)==' ') break;
                                tag_sb.append(html.charAt(j));
                                j++;
                            }
                            tag_sb.append('>');
                            // Add to anchor HashMap or modify existing one
                            long anchor_code = tag_sb.toString().hashCode();
                            if (anchor_hm.containsKey(anchor_code)) {
                                synchronized(lock2) {
                                    Pair<String, Integer> temp = anchor_hm.get(anchor_code);
                                    int counts = temp.getValue() + 1;
                                    Pair<String, Integer> temp_new = new Pair(tag_sb.toString(), counts);
                                    anchor_hm.replace(anchor_code, temp_new);
                                }  
                            } else {
                                synchronized(lock1) {
                                    anchor_hm.put(anchor_code, new Pair(tag_sb.toString(), 1));
                                    anchors.add(tag_sb.toString());
                                }
                            }
                        }
                        tag_counts++;
                        i++;
                        
                        // See if the tag just detected is an anchor tag
                        if (html.substring(i, i+2).toLowerCase().equals("a ")) {
                            a_flag = 1; // we are now looking at an anchor tag. Note that an anchor tag is never <a> but always <a ..... >
                            i = i + 2; // move the pointer two places forward
                        }                    
                    } catch (NullPointerException e) {
                        // Do nothing
                    }
                }
                
                // Search for anchor tags and associated URL
                if (path<=max_path) {
                    if (a_flag==1 && (html.substring(i, i+11).toLowerCase().equals("href=\"http:"))) { 
                        StringBuilder new_url = new StringBuilder();
                        int length_count = i+6; 
                        char temp = html.charAt(length_count); // starts from h in http
                        while (temp!='\"') { // ends when sees double quotes again
                            new_url.append(temp);
                            length_count++;
                            temp = html.charAt(length_count);
                        }

                        // Now that the URL is formed in new_url, we need to add it to the queue
                        String new_url_2 = new_url.toString();
                        Pair<String, Integer> p = new Pair(new_url_2, path);
                        queue.add(p);
                        
                        a_flag = 0; // end of hyperlink
                        i = length_count;
                        i++;
                    }  
                }
                i++;
            }
        } catch (MalformedURLException mue) {
            mue.printStackTrace();
            return tag_counts;
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return tag_counts;
        } finally {
            try {
                if (is != null) is.close(); 
                return tag_counts;
            } catch (IOException ioe) {
                // nothing to see here
            }
        }
        return tag_counts;
    }
    
    /**
     * @param args the command line arguments
     * ex: java HTMLTagCounter -pages 20 -path 2 someURL
     * @throws java.lang.InterruptedException
     */
    public static void main(String[] args) throws InterruptedException {
        
        String url = "";
        
        // Sleep is useful because it can be used to wait for a few seconds before proceeding to save the tag information in memory of the
        // current webpage, in case we have already met the parameter requirements
        for (int i = 0; i<args.length; i++) {
            if ("-pages".equals(args[i])) {
                try {
                    max_pages = Integer.parseInt(args[i+1]);
                    i = i+1;
                } catch (NumberFormatException e) {
                    s_logger.error("Illegal parameter arguments given.", e);
                }
            } else if ("-path".equals(args[i])) {
                try {
                    max_path = Integer.parseInt(args[i+1]);
                    s_logger.info("The path is "+max_path);
                    i = i+1;
                } catch (NumberFormatException e) {
                    s_logger.error("Illegal parameter arguments given.", e);
                }
            } else {
                try {
                url = args[i];
                } catch (NullPointerException e) {
                    s_logger.info("NO URL provided.", e);
                }
            }
        }
        
        if (!"".equals(url)) {
            Pair<String, Integer> p = new Pair(url, 1);
            queue.add(p);  
            while (!Thread.interrupted() && (seen_urls.size())<max_pages && Thread.activeCount()!=0) {
                if (queue.size()!=0 && Thread.activeCount()<=(max_pages-seen_urls.size())) {
                    s_logger.info("Starting out thread. Total active threads:"+Thread.activeCount());
                    (new Thread(new HTMLTagCounter())).start();
                } 
            }
            
            s_logger.info("Size of seen URLS is "+seen_urls.size());
            s_logger.info("Size of max pages is "+max_pages);
            for (Pair<String, Integer> tc : seen_urls.values()) {
                s_logger.info(tc.getKey()+", "+tc.getValue());
            }
            
            s_logger.info("Now printing out tags seen and their respective counts");
            for (String s : anchors) {
                long s_hc = s.hashCode();
                s_logger.info(s+": "+anchor_hm.get(s_hc).getValue());
            }
            
            s_logger.info("Program terminated.");
        } else {
            s_logger.debug("No starting URL provided. Program Terminated.");
        }
    }
}
