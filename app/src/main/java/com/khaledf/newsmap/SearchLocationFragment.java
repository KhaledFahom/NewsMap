package com.khaledf.newsmap;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.support.v4.app.DialogFragment;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.ListView;

import java.util.ArrayList;
import java.util.Timer;
import java.util.TimerTask;

import com.google.android.gms.maps.model.Marker;

public class SearchLocationFragment extends DialogFragment {
    private View view;
    private EditText inputBox;
    private static ArrayList<Marker> displayedMarkers;
    private static ArrayList<String> displayedMarkersHeadlines;
    private ArrayList<Marker> results;
    private InputMethodManager inputManager;
    private SearchPreviewAdapter listAdapter;
    private String searchQuery;
    private SearchTask task;

    
    static SearchLocationFragment newInstance(ArrayList<Marker> markers) {
    	String title;
    	Marker marker;
    	SearchLocationFragment fragment = new SearchLocationFragment();
    	displayedMarkers = new ArrayList<Marker>();
    	displayedMarkersHeadlines = new ArrayList<String>();
    	for(int i = 0; i < markers.size(); i++) {
    		marker = markers.get(i);
    		title = marker.getTitle();
    		if(!displayedMarkersHeadlines.contains(title)) {
        		displayedMarkersHeadlines.add(title);
        		displayedMarkers.add(marker);
    		}
    	}
        return fragment;
    }

    public SearchLocationFragment() {
        // Empty constructor required for DialogFragment
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        view = inflater.inflate(R.layout.fragment_search_location, container);
        inputBox = (EditText) view.findViewById(R.id.search_input);
        inputManager = (InputMethodManager) getActivity().getSystemService(Context.INPUT_METHOD_SERVICE);
        inputManager.toggleSoftInput(InputMethodManager.SHOW_FORCED, InputMethodManager.HIDE_IMPLICIT_ONLY);
        getDialog().getWindow().requestFeature(Window.FEATURE_NO_TITLE);
        inputBox.requestFocus();

        inputBox.addTextChangedListener(
                new TextWatcher() {
                    @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
                    @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
                    private Timer timer = new Timer();
                    private final long DELAY = 1000; // milliseconds

                    @Override
                    public void afterTextChanged(final Editable s) {
                        timer.cancel();
                        timer = new Timer();
                        timer.schedule(
                                new TimerTask() {
                                    @Override
                                    public void run() {
                                        inputManager.hideSoftInputFromWindow(inputBox.getWindowToken(), 0);
                                        searchQuery = inputBox.getText().toString();
                                        if(getActivity() == null) 
                                        	return;
                                        getActivity().runOnUiThread(new Runnable() {
                                            @Override
                                            public void run() {
                                                if(inputBox != null) {
                                                    inputBox.getText().clear();
                                                }
                                            }
                                        });
                                        if(searchQuery.length() < 1) 
                                        	return;
                                        task = new SearchTask();
                                        startMySearchTask(task);
                                    }
                                },
                                DELAY
                        );
                    }
                }
        );
        results = new ArrayList<Marker>();
        listAdapter = new SearchPreviewAdapter(getActivity(), results);
        ListView markersView = (ListView) view.findViewById(R.id.search_list);
        markersView.setAdapter(listAdapter);
        markersView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) { 
                Intent browserIntent = new Intent(Intent.ACTION_VIEW, 
						Uri.parse(results.get(position).getSnippet()));
                startActivity(browserIntent);
            }
        });
        return view;
    }

    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    static void startMySearchTask(AsyncTask<Void, Void, Void> asyncTask) {
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB)
            asyncTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        else
            asyncTask.execute();
    }
    
    public class SearchTask extends AsyncTask<Void, Void, Void> {
    	
        @Override
        protected Void doInBackground(Void... params) {
        	results.clear();
        	if(displayedMarkers.size() != displayedMarkersHeadlines.size())
        		return null;
        	for(int i = 0; i < displayedMarkersHeadlines.size(); i++) {
            	if(displayedMarkersHeadlines.get(i).toLowerCase().contains(searchQuery.toLowerCase())) {
                    results.add(displayedMarkers.get(i));
            	}
            }
			return null;
        }

        @Override
        protected void onPreExecute() {
        }

        @Override
        protected void onPostExecute(Void result) {
            listAdapter.notifyDataSetChanged();
            task = null;
        }
    }
    
    @Override
    public void onStop() {
        InputMethodManager inputManager = (InputMethodManager) getActivity().getSystemService(Context.INPUT_METHOD_SERVICE);
        inputManager.hideSoftInputFromWindow(getActivity().findViewById(R.id.map).getWindowToken(), 0);
        super.onStop();
    }

}