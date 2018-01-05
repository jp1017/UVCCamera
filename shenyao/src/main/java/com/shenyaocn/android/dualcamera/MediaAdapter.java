package com.shenyaocn.android.dualcamera;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import com.shenyaocn.android.dualcamera.R;

import java.util.List;

/**
 * Created by ${meiqaunchuan} on 2017/4/28.
 */

public class MediaAdapter extends BaseAdapter {
    private List<String> mList;
    private Context mContext;

    public MediaAdapter(List<String> mList, Context mContext) {
        this.mList = mList;
        this.mContext = mContext;
    }

    @Override
    public int getCount() {
        return mList == null ? 0 : mList.size();
    }

    @Override
    public Object getItem(int i) {
        if (mList == null || mList.size() == 0) {
            return null;
        }
        return mList.get(i);
    }

    @Override
    public long getItemId(int i) {
        return i;
    }

    @Override
    public View getView(int i, View view, ViewGroup viewGroup) {
        viewHolder viewHolder = null;
        if (viewHolder == null) {
            viewHolder = new viewHolder();
            view = LayoutInflater.from(mContext).inflate(R.layout.video_text, null);
            viewHolder.tv = (TextView) view.findViewById(R.id.tv_video);
            viewHolder.tv.setTag(view);
        } else {
            view = (View) viewHolder.tv.getTag();
        }
//        view.setBackgroundColor(Color.parseColor("#64ffffff"));
        viewHolder.tv.setText(mList.get(i));
        return view;
    }

    class viewHolder {
        TextView tv;
    }
}
