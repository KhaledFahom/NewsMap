package com.khaledf.newsmap;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.StringTokenizer;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.support.v4.app.FragmentActivity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.ImageButton;
import android.widget.TextView;

import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.GoogleMap.InfoWindowAdapter;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.Polyline;

import static com.khaledf.newsmap.Utility.*;

public class MapActivity extends FragmentActivity
        implements OnMapReadyCallback {

    /******************** Static Members ********************/

    /* Async task that gets the HTML page, extracts headlines,
     * URLs and locations, then displays them on the map. */
    public static PipelineTask activePipelineTask = null;

    /* A list of all possible connection URLs for fetching articles.
     * Add more in 'initializeConnectionURLs()' function should you like it.
     * On first run, multiple 'ConnectAndDisplayTask' objects are run in
     * sequence on all URLs in 'connectionURLs' list.
     * If more events are requested, the same sequential run is called on
     * all the URLs in 'nextConnectionURLs' list. */
    public static ArrayList<String> connectionURLs = null;
    public static ArrayList<String> nextConnectionURLs = null;

    /* Used to store markers after displaying them.
     * Required for the OnClick event. (to display URL and such) */
    public static ArrayList<Marker> markers = null;

    /* Flag booleans used as arguments to control marker display events,
     * flag connection tasks as Initial or Chained connections, and specify
     * the required state of UI buttons. (Toggle on/off) */
    public static boolean DISPLAY_IN_CHUNKS = false, DISPLAY_ALL = true,
            FIRST_TASK = false, CHAINED_TASK = true,
            TOGGLE_OFF = false, TOGGLE_ON = true;

    /* Map object, used to synchronize with Google Maps API connection and
     * display their glorious map. */
    public static GoogleMap map = null;

    /* Hashtables for determining locations. One contains countries,
     * the other contains (major) cities. Values are taken from 2 CSV
     * asset files and inserted at initialization. Given a single word,
     * we'll use it as a key determine if it relates to an actual location. */
    public static Hashtable<String, String> countryTable = null, cityTable = null;

    /* 'maxCoordinates': the maximum number of coordinates per event. */
    public final static int maxCoordinates = 100, maximumMarkersOnMap = 1000,
            CONNETION_TOAST_TYPE = 0, MAP_TOAST_TYPE = 1, ERROR_TOAST_TYPE = 2,
            MAX_EVENTS_TOAST_TYPE = 3;

    /* Three UI buttons. */
    public static ImageButton searchButton, discoverButton, mapTypeButton;

    /* UI notification messages. (printed in Toast) */
    public static final String NEW_CONNECTION_TOAST_STRING = "Discovering More Events",
                                    END_CONNECTION_TOAST_STRING = "Discovery Completed",
                                    MAX_EVENTS_TOAST_STRING = "No More Current Events",
                                    ERROR_TOAST_STRING = "Connection error, try again later.";

    /******************** Private & Core Members ********************/

    /* Map display enum. */
    private final int STREET_MAP_TYPE = 0, SATELLITE_MAP_TYPE = 1,
            TERRAIN_MAP_TYPE = 2, HYBRID_MAP_TYPE = 3, MAP_TYPES_COUNT = 4;
    private int mapDisplayType;

    /*  Gotta wait like a peasant for 2000ms between subsequent connections
     *   to Reddit, otherwise the app gets severely rate-limited. */
    private static int MINIMUM_TIME_BETWEEN_REDDIT_REQUESTS = 2000;

    /* Required for delayed runnables */
    private static int safeConnectURLIndex;
    private static boolean safeConnectChainedCallFlag;

    /* Adapter for clickable marker popups. */
    private PopupAdapter popupAdapter = null;

    /* UI sync booleans, needed to restrict button presses while doing
     * network tasks. At first, Headline Search and Event Discovery
     * are disabled until the (initial) discovery task is finished. */
    private static boolean searchEnabled = false, discoveryEnabled = false;

    /* 'onMapReady()' starts the connection task. */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_maps);
        SupportMapFragment mapFragment = (SupportMapFragment)
                getSupportFragmentManager().findFragmentById(R.id.map);
        mapFragment.getMapAsync(this);
        activePipelineTask = null;
        initializeConnectionURLs();
        initializeHashTables();
        mapDisplayType = HYBRID_MAP_TYPE;
        markers = new ArrayList<Marker>();

        discoverButton = (ImageButton)findViewById(R.id.discover_button);
        discoverButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!discoveryEnabled) {
                    return;
                }
                startPipelineTaskSafely(0, CHAINED_TASK);
            }
        });
        searchButton = (ImageButton)findViewById(R.id.search_button);
        searchButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!searchEnabled)
                    return;
                android.support.v4.app.FragmentManager fm = getSupportFragmentManager();
                SearchLocationFragment newFragment = SearchLocationFragment.newInstance(markers);
                newFragment.show(fm, "fragment_search_location");
            }
        });
        mapTypeButton = (ImageButton)findViewById(R.id.change_map_type_button);
        mapTypeButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                cycleMapType();
            }
        });
        toggleDiscoveryButton(TOGGLE_OFF);
        toggleSearchButton(TOGGLE_OFF);


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
                //TODO: do something when polylines are pressed.
            }
        });
        startPipelineTaskSafely(0, FIRST_TASK);
    }

    /* Used on initial data connection and every time the user requests more articles.
     *  Set to 'Public Static' to allow PipelineTask to start another one of its kind
     *  after execution finishes. ("Chained Task") */
    public static void startPipelineTaskSafely(int URLIndex, boolean chainedCallFlag) {
        if((activePipelineTask != null) || (markers != null &&
                markers.size() > maximumMarkersOnMap)){
            printToast(MAX_EVENTS_TOAST_STRING, MAX_EVENTS_TOAST_TYPE);
            return;
        }
        safeConnectURLIndex = URLIndex;
        safeConnectChainedCallFlag = chainedCallFlag;
        toggleDiscoveryButton(TOGGLE_OFF);
        toggleSearchButton(TOGGLE_OFF);
        if(chainedCallFlag) {
            printToast(NEW_CONNECTION_TOAST_STRING, CONNETION_TOAST_TYPE);
        }

        Handler handler = new Handler();
        Runnable runnable = new Runnable() {
            public void run() {
                activePipelineTask = new PipelineTask(safeConnectURLIndex,
                        safeConnectChainedCallFlag);
                startMyTask(activePipelineTask);
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

    /******************** User Interface Members ********************/

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
            //..
        } else
        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE){
            //..
        }
    }

    /* Cycles through the 4 possible map display types. */
    private void cycleMapType() {
        mapDisplayType++;
        mapDisplayType = mapDisplayType % MAP_TYPES_COUNT;
        switch(mapDisplayType) {
            case HYBRID_MAP_TYPE:
                printToast("Hybrid Map", MAP_TOAST_TYPE);
                break;
            case TERRAIN_MAP_TYPE:
                printToast("Terrain Map", MAP_TOAST_TYPE);
                break;
            case SATELLITE_MAP_TYPE:
                printToast("Satellite Map", MAP_TOAST_TYPE);
                break;
            case STREET_MAP_TYPE:
                printToast("Street Map", MAP_TOAST_TYPE);
                break;
        }
        map.setMapType(mapDisplayType+1); //values: {1,2,3,4}, offset by 1
    }

    public static void toggleSearchButton(boolean function) {
        ImageButton button = searchButton;
        if(button == null) {
            return;
        }
        if(function == TOGGLE_ON) {
            button.getBackground().setAlpha(255);
            searchEnabled = true;
        } else {    // function == TOGGLE_OFF
            button.getBackground().setAlpha(64);
            searchEnabled = false;
        }
    }

    public static void toggleDiscoveryButton(boolean function) {
        ImageButton button = discoverButton;
        if(button == null) {
            return;
        }
        if(function == TOGGLE_ON) {
            button.getBackground().setAlpha(255);
            discoveryEnabled = true;
        } else {    // function == TOGGLE_OFF
            button.getBackground().setAlpha(64);
            discoveryEnabled = false;
        }
    }

    /******************** Classes ********************/

    /* An object that represents one recent news event. It could have
 * multiple locations involved, and thus multiple coordinates. */
    public static class EventObject {
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

    /******************** Utility Functions ********************/

}

