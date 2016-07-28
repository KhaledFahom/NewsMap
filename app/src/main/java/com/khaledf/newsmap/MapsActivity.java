package com.khaledf.newsmap;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.StringTokenizer;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.location.Address;
import android.location.Geocoder;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.support.v4.app.FragmentActivity;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.ImageButton;
import android.widget.TextView;

import com.github.johnpersano.supertoasts.SuperToast;
import com.github.johnpersano.supertoasts.util.Style;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.GoogleMap.InfoWindowAdapter;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;

public class MapsActivity extends FragmentActivity
        implements OnMapReadyCallback {

    /* Required for delayed runnables */
    private int safeConnectURLIndex;
    private boolean safeConnectChainedCallFlag;

    /*  We have to wait 2000ms between subsequent connections
    *   to Reddit, otherwise the app gets severely rate-limited. */
    private int DEFAULT_POLYLINE_WIDTH = 10, MINIMUM_TIME_BETWEEN_REDDIT_REQUESTS = 2000;

    /*  Required to keep track of the currently pressed polyline.
    *   Default Zindex is 0, and higher values trump lower ones. */
    private float maxZIndex = 1;
    private Polyline pressedPolyline = null;

    /*  Required to retry connecting to the current URL
        in case of connectivity loss. */
    private String lastUsedURL = "";

    /* Adapter for marker popups. */
    private PopupAdapter popupAdapter;

    /* A list of all possible connection URLs for fetching articles.
    * Add more in 'initializeConnectionURLs()' function should you like it.
    * First run, multiple 'ConnectAndDisplayTask' are run in sequence on
    * all URLs in 'connectionURLs' list, and then every time more events
    * are requested, the sequential run begins on all the URLs in
    * 'nextConnectionURLs' list. */
    private ArrayList<String> connectionURLs = null;
    private ArrayList<String> nextConnectionURLs = null;

    /* Hashtables for determining locations. One contains countries,
    * the other contains (major) cities. */
    Hashtable<String, String> countryTable = null;
    Hashtable<String, String> cityTable = null;

    /* Current fetched HTML document. Begins as the front page of
    * the subreddit, and as more get-requests are made, it moves
    * on to the Next page and so on. */
    private Document doc = null;

    /* Sync booleans, needed to restrict button presses while doing
    * network tasks. At first, headline search and event discovery
    * are disabled until the (initial) discovery task is finished. */
    private boolean searchEnabled = false, discoveryEnabled = false;
    private boolean DISPLAY_IN_CHUNKS = false, DISPLAY_ALL = true,
            FIRST_TASK = false, NON_FIRST_TASK = true,
            TOGGLE_OFF = false, TOGGLE_ON = true;

    /* Async task that gets the HTML page, extracts headlines,
    * URLs and locations, then displays them on the map. */
    private ConnectAndDisplayTask activeConnectionTask = null;

    /* 2 lists of events:
    *  - 'events' is used to store event objects retrieved
    * 	  in background thread.
    *  - 'displayEvents' is used as a queue for displaying events on the
    *    UI thread while the background thread still hasn't finished
    *    retrieving all the coordinates. BackgroundThread adds to the end,
    *    and UI thread removes from the front. Therefore we'll use LinkedList
     *    which has O(1) for add, peek and poll. */
    private ArrayList<EventObject> events = null;
    private LinkedList<EventObject> displayEvents = null;

    /* Map object, used to synchronize with Google Maps API connection. */
    private GoogleMap map = null;

    /* Map display enum. */
    private final int STREET_MAP_TYPE = 0, SATELLITE_MAP_TYPE = 1,
            TERRAIN_MAP_TYPE = 2, HYBRID_MAP_TYPE = 3, MAP_TYPES_NUM = 4,
            CONNETION_TYPE = 0, MAP_TYPE = 1;
    private int mapDisplayType;

    /* Used to store markers after displaying them.
    * Required for the OnClick event. (to display URL and such) */
    public ArrayList<Marker> markers = null;

    /* 'bodySizeByes', 'timeoutMS': for the HTTP request, set as infinite.
    * 'maxCoordinates': the maximum number of events to display on map.
    * 'redditDisplayChunkLimit': Reddit's default displayed entries per page.
    * 'displayChunkUnit': NewsMap's display size for mid-connection marking. */
    private int bodySizeBytes = 0, timeoutMS = 0, maxCoordinates = 100,
            redditDisplayChunkLimit = 25, displayChunkUnit = 10,
            maximumMarkersOnMap = 1000;

    /* An object that represents one recent news event. It could have
    * multiple locations involved, and thus multiple coordinates. */
    public class EventObject {
        public LatLng[] coordinates;
        public ArrayList<String> locations = null;
        public String URL = "";
        public String headline = "";

        public EventObject(String headline, String URL) {
            this.headline = headline;
            this.URL = URL;
            coordinates = new LatLng[maxCoordinates];
            this.locations = null;
        }
    }

    /* A receiver to detect changes in network connection. */
    static public class NetworkChangeReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(final Context context, final Intent intent) {
            ConnectivityManager cm = (ConnectivityManager) context
                                        .getSystemService(Context.CONNECTIVITY_SERVICE);

            NetworkInfo info = cm.getActiveNetworkInfo();
            if(info != null && info.getType() != ConnectivityManager.TYPE_WIFI
                    && info.getType() != ConnectivityManager.TYPE_MOBILE) {
                // Internet connection loss
            }
        }
    }

    /* Used for popup windows to display a custom popup
    * after clicking a marker. */
    private class PopupAdapter implements InfoWindowAdapter {
        LayoutInflater inflater = null;

        PopupAdapter(LayoutInflater inflater) {
            this.inflater = inflater;
        }

        @Override
        public View getInfoWindow(Marker marker) {
            return null;
        }

        @SuppressLint("InflateParams")
        @Override
        public View getInfoContents(Marker marker) {
            View popup = inflater.inflate(R.layout.popup, null);
            TextView titleTextView = (TextView)popup.findViewById(R.id.title);
            titleTextView.setText(marker.getTitle());
            titleTextView = (TextView)popup.findViewById(R.id.url);
            titleTextView.setText(marker.getSnippet());
            return(popup);
        }
    }

    /* Connects to the website and collects hot events, then
    fills  the 'events' array with all the informtaion,
    including coordinates.  */
    private class ConnectAndDisplayTask extends AsyncTask<String,Void,String> {
        /* Each task has to connect to a single URL, specified by
         * the 'URLIndex' which accesses 'MapActivity:connectionURLs' list,
         * or the first URL in 'nextConnectionURLs' list. */
        private int URLIndex;
        private String mainURL;
        private boolean taskFailure = false;

        /* A flag that specifies whether or not the current task is the first task
         * in a series of sequential tasks. First task always has 'false'
         * and the following tasks have 'true'. */
        private boolean chainedCallFlag;

        public ConnectAndDisplayTask(int URLIndex, boolean chainedCallFlag) {
            this.URLIndex = URLIndex;
            this.chainedCallFlag = chainedCallFlag;
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
   //         Log.d("URL1", "MAINURL IS "+mainURL);
   //         if(markers != null) {
   //             Log.d("URL","marker size is "+markers.size());
   //         }
        }

        @Override
        protected String doInBackground(String... params) {
            String nextPageURL = null;
            events = new ArrayList<EventObject>();
            displayEvents = new LinkedList<EventObject>();
            String URL = "", headline = "";
            int pendingGetRequests = (int) Math.ceil(maxCoordinates/redditDisplayChunkLimit);
            try {
        /* Connecting and filling 'rawElements' with HTML elements. */
                doc =  Jsoup.connect(mainURL).userAgent(App.USER_AGENT)
                        .maxBodySize(bodySizeBytes).timeout(timeoutMS).get();
                Elements rawElements = doc.select("div[data-type$=link]");
                nextPageURL = getNextPageURL(doc.select("a:contains(next)"));
                while(pendingGetRequests > 0) {
                    Thread.sleep(MINIMUM_TIME_BETWEEN_REDDIT_REQUESTS);
                    doc = Jsoup.connect(nextPageURL).maxBodySize(bodySizeBytes)
                            .timeout(timeoutMS).get();
                    rawElements.addAll(doc.select("div[data-type$=link]"));
                    nextPageURL = getNextPageURL(doc.select("a:contains(next)"));
                    pendingGetRequests--;
                }
                nextConnectionURLs.add(nextPageURL);
    //          Log.d("URL", "nextpg url is: "+nextPageURL);
        /* Building and collecting 'eventObject's from 'rawElements'.*/
                EventObject event = null;
                Element htmlNode = null;
                for(int j = 0; j < rawElements.size(); j++) {
                    htmlNode = rawElements.get(j).getElementsByClass("title").get(1);
                    URL = htmlNode.attr("href");
                    headline = htmlNode.text();
                    events.add(new EventObject(headline, URL));
                    if(j+1 == maxCoordinates) break;
                }

        /* Analyzing the events and filling in the coordinates. */
                for(int j = 0; j < events.size(); j++) {
                    event = events.get(j);
                    event.locations = getLocationsFromHeadline(event.headline);
                    findLngLat(j);
                    displayEvents.add(events.get(j));
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
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return "";
        }

        /* If more events are pending display, display them. */
        @Override
        protected void onPostExecute(String result) {
            if(!displayEvents.isEmpty()) {
                displayMarkers(DISPLAY_ALL);
            }
            activeConnectionTask = null;
            events = null;
            displayEvents = null;

            if(taskFailure) {
                printToast("That Will Be All For Today.", CONNETION_TYPE);
                toggleDiscoveryButton(TOGGLE_ON);
                toggleSearchButton(TOGGLE_ON);
                return;
            }

    /* Chain flag is 0 in case of series of initial connections,
     * and 1 in case of series of additional connections. */
            if((URLIndex+1) < connectionURLs.size()) {
                startConnectionTaskSafely(URLIndex+1, NON_FIRST_TASK);
            } else {
                printToast("Discovery Completed", CONNETION_TYPE);
                toggleDiscoveryButton(TOGGLE_ON);
                toggleSearchButton(TOGGLE_ON);
            }
        }

        @Override
        protected void onPreExecute() {
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
            for(int i = 0; i < elems.size(); i++){
                if(elems.get(i).attr("rel").contains("nofollow"))
                    return elems.get(i).attr("href");
            }
            return "";
        }
    }

    /* 'onMapReady()' starts the connection task. */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_maps);
        SupportMapFragment mapFragment = (SupportMapFragment)
                getSupportFragmentManager().findFragmentById(R.id.map);
        mapFragment.getMapAsync(this);
        activeConnectionTask = null;
        initializeConnectionURLs();
        initializeHashTables();
        mapDisplayType = HYBRID_MAP_TYPE;
        markers = new ArrayList<Marker>();

        toggleDiscoveryButton(TOGGLE_OFF);
        ImageButton moreButton = (ImageButton)findViewById(R.id.discover_button);
        moreButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!discoveryEnabled) {
                    return;
                }
                startConnectionTaskSafely(0, NON_FIRST_TASK);
            }
        });

        toggleSearchButton(TOGGLE_OFF);
        ImageButton searchHeadlinesButton = (ImageButton)findViewById(R.id.search_button);
        searchHeadlinesButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!searchEnabled)
                    return;
                android.support.v4.app.FragmentManager fm = getSupportFragmentManager();
                SearchLocationFragment newFragment = SearchLocationFragment.newInstance(markers);
                newFragment.show(fm, "fragment_search_location");
            }
        });

        ImageButton changeMapTypeButton = (ImageButton)findViewById(R.id.change_map_type_button);
        changeMapTypeButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                cycleMapType();
            }
        });
    }

    /* When the map is ready, sets an event listener for popup window events
    * and starts the connection task. */
    @Override
    public void onMapReady(GoogleMap map) {
        this.map = map;
        this.popupAdapter = new PopupAdapter(getLayoutInflater());
        this.map.setInfoWindowAdapter(this.popupAdapter);
        this.map.setOnInfoWindowClickListener(
                new GoogleMap.OnInfoWindowClickListener() {
                    @Override
                    public void onInfoWindowClick(Marker marker) {
                        Intent browserIntent = new Intent(Intent.ACTION_VIEW,
                                Uri.parse(marker.getSnippet()));
                        startActivity(browserIntent);
                    }
                });
        this.map.getUiSettings().setMapToolbarEnabled(false);
        this.map.setOnPolylineClickListener(new GoogleMap.OnPolylineClickListener() {
            @Override
            public void onPolylineClick(Polyline polyline) {
                /*
                // case: switching between 2 polylines.
                if((pressedPolyline != null) && (polyline.equals(pressedPolyline))) {
                    pressedPolyline.setZIndex(DEFAULT_POLYLINE_Z_INDEX);
                    pressedPolyline.setWidth(DEFAULT_POLYLINE_WIDTH);
                    pressedPolyline = polyline;
                    pressedPolyline.setZIndex(PRESSED_POLYLINE_Z_INDEX);
                    pressedPolyline.setWidth(PRESSED_POLYLINE_WIDTH);

                } else {
                    // case: pressing the same polyline.
                    if(pressedPolyline != null) {
                        polyline.setZIndex(DEFAULT_POLYLINE_Z_INDEX);
                        polyline.setWidth(DEFAULT_POLYLINE_WIDTH);
                        Log.d("china","same poly");
                    } else { // case: pressing the very first polyline.
                        pressedPolyline = polyline;
                        pressedPolyline.setZIndex(PRESSED_POLYLINE_Z_INDEX);
                        pressedPolyline.setWidth(PRESSED_POLYLINE_WIDTH);
                    }
                }
                */
            }
        });

        startConnectionTaskSafely(0, FIRST_TASK);
    }

    /* Displays the collected data on the map. Empties 'displayEvents' in
    * pre-defined chunks unless specified in the 2nd argument. */
    public void displayMarkers(boolean forceEmptyArrayFlag) {
        EventObject event = null;
        PolylineOptions lineOptions = null;
        Polyline line = null;
        Marker newMarker = null;
        int displayLimit, counter, redVal, greenVal, blueVal;
        float color, rotationRand;
        double lat, lng;

        Random randomNumGenerator = new Random();
        if(forceEmptyArrayFlag) {
            displayLimit = displayEvents.size();
        } else {
            displayLimit = displayChunkUnit;
        }
        float[] hsv = new float[3];
        for(int i = 0; (i < displayLimit) &&
                (i < displayEvents.size()); i++) {
    /* Each 'remove(0)' shifts the array left. Costly operation but
     * necessary for thread-safeness. (always adding at the end,
       always removing from the beginning) */
            event = displayEvents.poll();
            color = randomNumGenerator.nextFloat()*360;
            rotationRand = randomNumGenerator.nextFloat()*90 - 90/2;
            redVal = randomNumGenerator.nextInt(255);
            greenVal = randomNumGenerator.nextInt(255);
            blueVal = randomNumGenerator.nextInt(255);
            Color.RGBToHSV(redVal, greenVal, blueVal, hsv);
            color = hsv[0];
            lineOptions = new PolylineOptions().width(DEFAULT_POLYLINE_WIDTH).geodesic(true)
                    .color(Color.argb(255, redVal, greenVal, blueVal));
            counter = 0;
            for(int j = 0; j < event.locations.size(); j++) {
                lat = event.coordinates[j].latitude;
                lng = event.coordinates[j].longitude;
                if(lat == -1 || lng == -1) {
                    continue;
                }
                lineOptions.add(new LatLng(lat, lng));
                counter++;
                newMarker = map.addMarker(new MarkerOptions()
                        .snippet(event.URL)
                        .position(new LatLng(lat, lng))
                        .rotation(rotationRand)
                        .title(event.headline));
                newMarker.setIcon(BitmapDescriptorFactory.
                        defaultMarker(color));
                markers.add(newMarker);
            }
            if(counter > 1) {
                line = map.addPolyline(lineOptions);
                line.setClickable(true);
                line.setWidth(DEFAULT_POLYLINE_WIDTH);
            }
        }
    }

    /* Catching hardware keypresses. */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e) {
        if((keyCode == KeyEvent.KEYCODE_BACK) || (keyCode == KeyEvent.KEYCODE_MENU)) {
            finish();
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(0);
        }
        return super.onKeyDown(keyCode, e);
    }

    /* Catching orientation change. */
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if(newConfig.orientation == Configuration.ORIENTATION_PORTRAIT) {
        } else
        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE){
        }
    }

    /* Used on initial data connection and every time the user presses
    * the volume+ button. */
    private void startConnectionTaskSafely(int URLIndex, boolean chainedCallFlag) {
        if((activeConnectionTask != null) || (markers != null &&
                                            markers.size() > maximumMarkersOnMap)){
            printToast("No More Current Events", CONNETION_TYPE);
            return;
        }
        safeConnectURLIndex = URLIndex;
        safeConnectChainedCallFlag = chainedCallFlag;
        toggleDiscoveryButton(TOGGLE_OFF);
        toggleSearchButton(TOGGLE_OFF);
        if(chainedCallFlag) {
            printToast("Discovering More Events", CONNETION_TYPE);
        }

        Handler handler = new Handler();
        Runnable runnable = new Runnable() {
            public void run() {
                activeConnectionTask = new ConnectAndDisplayTask(safeConnectURLIndex, safeConnectChainedCallFlag);
                startMyTask(activeConnectionTask);
            }
        };
        handler.postDelayed(runnable, MINIMUM_TIME_BETWEEN_REDDIT_REQUESTS);
    }

    /* Initializes the location hashtables.
    * Code duplication warning. */
    private void initializeHashTables() {
        countryTable = new Hashtable<String, String>();
        cityTable = new Hashtable<String, String>();
        String line = "", token = "", country = "", city = "", keyword = "";
        StringTokenizer tokens, keywordTokens;
        try {
            AssetManager manager = getApplicationContext().getAssets();
            InputStream stream = manager.open("country.csv");
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
            line = reader.readLine();
            while(line != null) {
                tokens = new StringTokenizer(line, "\"");
                token = (String)tokens.nextElement();
                country = token.substring(0, token.length()-1);
                token = (String)tokens.nextElement();
                keywordTokens = new StringTokenizer(token, ",");
                for(int i = 0; i <= keywordTokens.countTokens(); i++) {
                    keyword = (String)keywordTokens.nextElement();
                    keyword = keyword.trim();
                    if(keyword.length() < 2) {
                        continue; //just in case
                    }
                    countryTable.put(keyword, country);
                }
                line = reader.readLine();
            }
            stream.close();
            stream = manager.open("city.csv");
            reader = new BufferedReader(new InputStreamReader(stream));
            line = reader.readLine();
            while(line != null) {
                tokens = new StringTokenizer(line, "\"");
                city = (String)tokens.nextElement();
                token = (String)tokens.nextElement();		//second element is a comma
                token = (String)tokens.nextElement();
                keywordTokens = new StringTokenizer(token, ",");
                for(int i = 0; i <= keywordTokens.countTokens(); i++) {
                    keyword = (String)keywordTokens.nextElement();
                    keyword = keyword.trim();
                    if(keyword.length() < 2) {
                        continue; //just in case
                    }
                    cityTable.put(keyword, city);
                }
                line = reader.readLine();
            }
            stream.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /* Initializes the 'connectionURLs' list. */
    private void initializeConnectionURLs() {
        connectionURLs = new ArrayList<String>();
        nextConnectionURLs = new ArrayList<String>();
        connectionURLs.add("http://www.reddit.com/r/WorldNews");
//		connectionURLs.add("http://www.reddit.com/r/GeoPolitics");
//		connectionURLs.add("http://www.reddit.com/r/WorldPolitics");
    }

    /* Cycles through the 4 possible map display types. */
    private void cycleMapType() {
        mapDisplayType++;
        mapDisplayType = mapDisplayType % MAP_TYPES_NUM;
        switch(mapDisplayType) {
            case HYBRID_MAP_TYPE:
                printToast("Hybrid Map", MAP_TYPE);
                break;
            case TERRAIN_MAP_TYPE:
                printToast("Terrain Map", MAP_TYPE);
                break;
            case SATELLITE_MAP_TYPE:
                printToast("Satellite Map", MAP_TYPE);
                break;
            case STREET_MAP_TYPE:
                printToast("Street Map", MAP_TYPE);
                break;
        }
        map.setMapType(mapDisplayType+1); //values: {1,2,3,4}, offset by 1
    }

    /* Finds the event in 'events' by index and makes an API call to get
    * coordinates from location names in the event, and fills the
    * location data in the event object. */
    private void findLngLat(int index) {
        Geocoder geocoder = new Geocoder(getApplicationContext());
        EventObject event = events.get(index);
        int counter = 0;
        String location = null;
        try {
            for(int i = 0; i < event.locations.size(); i++) {
                location = event.locations.get(i);
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
                    LatLng newCoordinates = new LatLng(results.get(0).getLatitude(), results.get(0).getLongitude());
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
        StringTokenizer tokens = new StringTokenizer(headline);
        String token = "", location = "";
        Set<String> newSet = new HashSet<String>();

/* Tokenizing the headline and trimming each token's beginning and end
 * in case of quotes. All tokens not beginning with capital letters
 * are discarded.
 */
        for(int i = 0; i < tokens.countTokens(); i++) {
            token = (String)tokens.nextElement();
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
        for(int i = 0; i < keywords.size(); i++) {
            token = keywords.get(i);
            if(token.equals("The") || token.equals("the") || token.equals("THE"))
                continue;
            location = cityTable.get(token);
            if(location == null) {
                location = countryTable.get(token);
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

    public void toggleSearchButton(boolean flag) {
        ImageButton button = (ImageButton)findViewById(R.id.search_button);
        if(flag == true) {
            button.getBackground().setAlpha(255);
            searchEnabled = true;
        } else {
            button.getBackground().setAlpha(64);
            searchEnabled = false;
        }
    }

    public void toggleDiscoveryButton(boolean flag) {
        ImageButton button = (ImageButton)findViewById(R.id.discover_button);
        if(flag == true) {
            button.getBackground().setAlpha(255);
            discoveryEnabled = true;
        } else {
            button.getBackground().setAlpha(64);
            discoveryEnabled = false;
        }
    }

    /******************** UTILITY FUNCTIONS ********************/

    /* Converts an RGB color integer into a 32-bit ARGB int, with
        alpha=255. */
    private int RGB_To_ARGB(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = (rgb >> 0) & 0xFF;
        return 0xff000000 | (r << 16) | (g << 8) | b;
    }

    /* Prints a toast message. */
    private void printToast(String msg, int type) {
        int MY_DURATION = 500, MY_Y_OFFSET = 350;
        SuperToast toast = null;
        if(msg.equals("That Will Be All For Today.")) {
            MY_DURATION *=3;
        }
        if(type == CONNETION_TYPE) {
            toast = SuperToast.create(this, msg, MY_DURATION,
                    Style.getStyle(Style.BLUE, SuperToast.Animations.FADE));
        } else if(type == MAP_TYPE) {
            toast = SuperToast.create(this, msg, MY_DURATION,
                    Style.getStyle(Style.ORANGE, SuperToast.Animations.FADE));
        }
        toast.setGravity(Gravity.BOTTOM, 0, MY_Y_OFFSET);
        toast.show();
    }

    /* Used to start asynchronous tasks safely, in case of really old API. */
    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    private void startMyTask(AsyncTask<String, Void, String> asyncTask) {
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB)
            asyncTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        else
            asyncTask.execute();
    }
}
