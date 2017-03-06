package com.khaledf.newsmap;

import android.graphics.Color;
import android.location.Address;
import android.location.Geocoder;
import android.os.AsyncTask;

import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.StringTokenizer;

import static com.khaledf.newsmap.MapActivity.*;
import static com.khaledf.newsmap.Utility.*;


/* -Background thread: Connects to the website and collects articles, then
 * fills  the 'events' array with geographically identified events.
 * -UI thread: Intermittently runs during background processing to place
  * markers on the map in chunks. Once the background thread finishes, it
  * dumps all of the remaining markers at once. */
public class PipelineTask extends AsyncTask<String,Void,String> {
    /* Two lists of events that make up the Network & UI pipeline:
  *  - 'events' is used to store event objects retrieved
  * 	  from the network in background thread.
  *  - 'displayEvents' is used as a queue for displaying events on the
  *    UI thread while the background thread is still calculating
  *    coordinates for other events. BackgroundThread adds to the end,
  *    and UI thread removes from the front. Therefore we'll use LinkedList
  *    which has O(1) for add, peek and poll. */
    private ArrayList<MapActivity.EventObject> events = null;
    private LinkedList<MapActivity.EventObject> displayEvents = null;

    /* Current fetched HTML document. Begins as the front page of
    * the subreddit, and as more article requests are made, it moves
    * on to the Next page and so on. */
    private Document doc = null;

    /* Each task has to connect to a single URL, specified by
     * the 'URLIndex' which accesses 'MapActivity:connectionURLs' list,
     * or the first URL in 'nextConnectionURLs' list. */
    private int URLIndex;
    private String mainURL;
    private boolean taskFailure = false;
    /*  Required to retry connecting to the current URL
     *  in case of connectivity loss. */
    private String lastUsedURL = "";

    /* A flag that specifies whether or not the current task is the first task
     * in a series of sequential tasks. First task always has 'false'
     * and the following tasks have 'true'. */
    private boolean chainedCallFlag;

    /* 'bodySizeByes', 'timeoutMS': for the HTTP request, set as infinite.
     * 'redditDisplayChunkLimit': Reddit's default displayed entries per page.
     * 'displayChunkUnit': NewsMap's display size for mid-connection marking.
     * 'maxCoordinates': the maximum number of coordinates per event. */
    private int polylineWidth = 10, bodySizeBytes = 0, timeoutMS = 0,
            maxCoordinates = 100, redditDisplayChunkLimit = 25, displayChunkUnit = 10;

    public PipelineTask(int URLIndex, boolean chainedCallFlag) {
        this.URLIndex = URLIndex;
        this.chainedCallFlag = chainedCallFlag;
            /* Each task determines its own connection URL. */
        if(chainedCallFlag) {
            if(nextConnectionURLs.size() > 0) {
                mainURL = nextConnectionURLs.remove(0);
            } else {
                    /* this case is possible only if internet connection was lost
                       during a previous connection task. As such, there's no 'nextPageUrl[0]'
                       and the size is 0, thus the URL stays the same as last time. (retry)*/
                mainURL = lastUsedURL;
            }
        } else {
            mainURL = connectionURLs.get(URLIndex);
        }
        lastUsedURL = mainURL;
    }

    @Override
    protected void onPreExecute() {
        //..
    }

    @Override
    protected String doInBackground(String... params) {
        String nextPageURL = null;
        events = new ArrayList<MapActivity.EventObject>();
        displayEvents = new LinkedList<MapActivity.EventObject>();
        String URL = "", headline = "";
        int pendingGetRequests = (int) Math.ceil(maxCoordinates/redditDisplayChunkLimit);
        try {
        /* Connecting and filling 'rawElements' with HTML elements. */
            doc =  Jsoup.connect(mainURL).userAgent(App.USER_AGENT)
                    .maxBodySize(bodySizeBytes).timeout(timeoutMS).get();
            Elements rawElements = doc.select("a[data-event-action$=title]");
            nextPageURL = getNextPageURL(doc.select("a:contains(next)"));
            nextConnectionURLs.add(nextPageURL);
        /* Building and collecting 'eventObject's from 'rawElements'.*/
            for(Element htmlNode : rawElements) {
                URL = htmlNode.attr("href");
                headline = htmlNode.text();
                events.add(new EventObject(headline, URL));
                if(events.size() == maxCoordinates) {
                    break;
                }
            }
        /* Analyzing the events and filling in the coordinates. */
            for(MapActivity.EventObject event : events) {
                event.locations = getLocationsFromHeadline(event.headline);
                findLngLat(event);
                displayEvents.add(event);
                if(displayEvents.size() >= displayChunkUnit) {
                    publishProgress();
                }
            }
        } catch(RuntimeException e){
            e.printStackTrace();
            taskFailure = true;
        } catch (IOException e) {
            e.printStackTrace();
            taskFailure = true;
        }
        return "";
    }

    /* Called once we have at least the minimum ammount of events
 * pending display. */
    @Override
    protected void onProgressUpdate(Void... values) {
        displayMarkers(DISPLAY_IN_CHUNKS);
    }

    /* Receives a list of elements and finds the one linking
     * to the next page, as per Reddit HTML attributes. */
    private String getNextPageURL(Elements elems) {
        for(Element element : elems) {
            if(element.attr("rel").contains("nofollow")) {
                return element.attr("href");
            }
        }
        return "";
    }

    /* If any events are pending display, display them all at once. */
    @Override
    protected void onPostExecute(String result) {
        if(!displayEvents.isEmpty()) {
            displayMarkers(DISPLAY_ALL);
        }
        activePipelineTask = null;
        events = null;
        displayEvents = null;
        if(taskFailure) {
            printToast(ERROR_TOAST_STRING, ERROR_TOAST_TYPE);
            toggleDiscoveryButton(TOGGLE_ON);
            toggleSearchButton(TOGGLE_ON);
            return;
        }

        /* Chain flag is 0 in case of series of initial connections,
         * and 1 in case of series of additional connections. */
        if((URLIndex+1) < connectionURLs.size()) {
            startPipelineTaskSafely(URLIndex+1, CHAINED_TASK);
        } else {
            printToast(END_CONNECTION_TOAST_STRING, CONNETION_TOAST_TYPE);
            toggleDiscoveryButton(TOGGLE_ON);
            toggleSearchButton(TOGGLE_ON);
        }
    }

    /* Displays the collected data on the map. Empties 'displayEvents' in
     * pre-defined chunks unless specified in the 2nd argument. */
    private void displayMarkers(boolean forceEmptyArrayFlag) {
        MapActivity.EventObject event = null;
        PolylineOptions lineOptions = null;
        Polyline line = null;
        Marker newMarker = null;
        int displayLimit, locationCount, redVal, greenVal, blueVal;
        float color, rotationRand;
        double lat, lng;

        Random randomNumGenerator = new Random();
        if(forceEmptyArrayFlag == DISPLAY_ALL) {
            displayLimit = displayEvents.size();
        } else {    // forceEmptyArrayFlag==DISPLAY_IN_CHUNKS
            displayLimit = displayChunkUnit;
        }
        float[] hsv = new float[3];
        for(int i = 0; (i < displayLimit) && (i < displayEvents.size()); i++) {
            event = displayEvents.poll();
            if((event == null) || (event.coordinates == null)){
                continue;
            }
            //color = randomNumGenerator.nextFloat()*360;
            rotationRand = randomNumGenerator.nextFloat()*90 - 90/2;
            redVal = randomNumGenerator.nextInt(255);
            greenVal = randomNumGenerator.nextInt(255);
            blueVal = randomNumGenerator.nextInt(255);
            Color.RGBToHSV(redVal, greenVal, blueVal, hsv);
            color = hsv[0];
            lineOptions = new PolylineOptions().width(polylineWidth).geodesic(true)
                    .color(Color.argb(255, redVal, greenVal, blueVal));
            locationCount = 0;
            for(LatLng coordinate : event.coordinates) {
                if(coordinate == null) {
                    continue;
                }
                lat = coordinate.latitude;
                lng = coordinate.longitude;
                if (lat == -1 || lng == -1) {
                    continue;
                }
                lineOptions.add(new LatLng(lat, lng));
                locationCount++;
                newMarker = map.addMarker(new MarkerOptions().snippet(event.URL)
                        .position(new LatLng(lat, lng)).rotation(rotationRand)
                        .title(event.headline));
                newMarker.setIcon(BitmapDescriptorFactory.defaultMarker(color));
                markers.add(newMarker);
            }
            if(locationCount > 1) {
                line = map.addPolyline(lineOptions);
                line.setClickable(true);
                line.setWidth(polylineWidth);
            }
        }
    }


    /* Finds the event in 'events' by index and makes an API call to get
     * coordinates from location names in the event, and fills the
     * location data in the event object. */
    private void findLngLat(MapActivity.EventObject event) {
        Geocoder geocoder = new Geocoder(App.appContext);
        int counter = 0;
        try {
            for(String location : event.locations) {
                if(location == null) {
                    continue;
                }
                if(location.equals("unknown")) {
                    LatLng unknownCoordinates = new LatLng(-1, -1);
                    event.coordinates[counter] = unknownCoordinates;
                    break;
                }
                List<Address> results = geocoder.getFromLocationName(location, 1);
                if(!results.isEmpty()) {
                    LatLng newCoordinates = new LatLng(results.get(0).getLatitude(),
                                                    results.get(0).getLongitude());
                    event.coordinates[counter] = newCoordinates;
                    counter++;
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /* Finds location by hashtable reads. O(k), k= number of words in headline */
    private ArrayList<String> getLocationsFromHeadline(String headline) {
        ArrayList<String> keywords = new ArrayList<String>();
        ArrayList<String> locations = new ArrayList<String>();
        StringTokenizer headlineTokens = new StringTokenizer(headline);
        String token = "", location = "";
        Set<String> newSet = new HashSet<String>();

        /* Tokenizing the headline and trimming each token's beginning and end
         * in case of quotes. All tokens not beginning with capital letters
         * are ignored. */
        for(int i = 0; i < headlineTokens.countTokens(); i++) {
            token = (String)headlineTokens.nextElement();
            if(token.charAt(0) == '\"' || token.charAt(0) == '\'')
                token = token.substring(1);
            if(token.charAt(token.length()-1) == '\'' ||
                    token.charAt(token.length()-1) == '\"')
                token = token.substring(0, token.length()-2);
            if(Character.isUpperCase(token.charAt(0))) {
                keywords.add(token);
            }
        }

        /* In case no tokens are usable, we return "unknown" as a location. */
        if(keywords.isEmpty()) {
            locations.add("unknown");
            return locations;
        }

        /* For each token, we'll look in the hashtables and
         * try to match a location. */
        for(String keyword : keywords) {
            if(keyword.toLowerCase().equals("the"))
                continue;
            location = cityTable.get(keyword);
            if(location == null) {
                location = countryTable.get(keyword);
                if(location == null) {
                    continue;
                } else {
                    locations.add(location);
                }
            } else {
                locations.add(location);
            }
        }
        if(locations.isEmpty()) {
            locations.add("unknown");
        } else {	//clearing duplicate entries
            newSet.addAll(locations);
            locations.clear();
            locations.addAll(newSet);
        }
        return locations;
    }
}