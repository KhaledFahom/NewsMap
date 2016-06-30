package com.khaledf.newsmap;

import android.content.Context;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import java.util.ArrayList;

import com.google.android.gms.maps.model.Marker;

public class SearchPreviewAdapter extends ArrayAdapter<Marker> {
	
    public SearchPreviewAdapter(Context context, ArrayList<Marker> array) {
        super(context, 0, array);
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
       	Marker marker = (Marker)getItem(position);
        if (convertView == null) {
            convertView = LayoutInflater.from(getContext()).inflate(R.layout.search_preview, parent, false);
        }
        convertView.setMinimumHeight(160);          // Unit is in pixels.
        TextView title = (TextView) convertView.findViewById(R.id.event_title);
        title.setText(marker.getTitle());
        if(position % 2 == 0) {
        	title.setTextColor(Color.rgb(192,202,51));
        } else {
        	title.setTextColor(Color.rgb(253,216,53));
        }
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        return convertView;
    }
}
