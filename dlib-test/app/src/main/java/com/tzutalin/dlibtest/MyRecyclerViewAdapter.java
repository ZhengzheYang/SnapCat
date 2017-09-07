package com.tzutalin.dlibtest;

import android.content.Context;
import android.graphics.Bitmap;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import java.util.ArrayList;


/**
 * Created by mangod on 7/21/17.
 */

public class MyRecyclerViewAdapter extends RecyclerView.Adapter<MyRecyclerViewAdapter.ViewHolder> {
//    private ArrayList<String> mData = new ArrayList<>();
    private ArrayList<Bitmap> mBitmap = new ArrayList<>();
    private LayoutInflater mInflater;
    private ItemClickListener mClickListener;

    // data is passed into the constructor
    public MyRecyclerViewAdapter(Context context, ArrayList<Bitmap> bitmap) {
        this.mInflater = LayoutInflater.from(context);
        this.mBitmap = bitmap;
    }

    // inflates the cell layout from xml when needed
    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view = mInflater.inflate(R.layout.grid_item, parent, false);
        ViewHolder viewHolder = new ViewHolder(view);
        return viewHolder;
    }

    // binds the data to the textview in each cell
    @Override
    public void onBindViewHolder(ViewHolder holder, int position) {
        Bitmap pic = mBitmap.get(position);
        holder.myImageView.setImageBitmap(pic);
    }

    // total number of cells
    @Override
    public int getItemCount() {
        return mBitmap.size();
    }


    // stores and recycles views as they are scrolled off screen
    public class ViewHolder extends RecyclerView.ViewHolder implements View.OnClickListener {
        public ImageView myImageView;

        public ViewHolder(View itemView) {
            super(itemView);
            myImageView = (ImageView) itemView.findViewById(R.id.cell);
            myImageView.setAlpha(1f);
            itemView.setOnClickListener(this);
            ItemClickListener clickEvent = new ItemClickListener() {
                @Override
                public void onItemClick(View view, int position) {
                    if(position == 0) {
                        OnClickBooleans.drawSunglasses = false;
                        OnClickBooleans.drawBeard = false;
                        OnClickBooleans.drawDogFace = false;
                        OnClickBooleans.drawFlowerCrown = false;
                        OnClickBooleans.drawGlassesPlus1 = false;
                        OnClickBooleans.drawKingCrown = false;
                        OnClickBooleans.drawPigNose = false;
                        OnClickBooleans.drawBeard1 = false;
                        OnClickBooleans.drawBatmanMask = false;
                    }
                    if(position == 1) {
                        if(OnClickBooleans.drawSunglasses == false)
                            OnClickBooleans.drawSunglasses = true;
                        else
                            OnClickBooleans.drawSunglasses = false;
                    }
                    if(position == 2) {
                        if(OnClickBooleans.drawBeard == false)
                            OnClickBooleans.drawBeard = true;
                        else
                            OnClickBooleans.drawBeard = false;
                    }
                    if(position == 3) {
                        if(OnClickBooleans.drawDogFace == false)
                            OnClickBooleans.drawDogFace = true;
                        else
                            OnClickBooleans.drawDogFace = false;
                    }
                    if(position == 4) {
                        if(OnClickBooleans.drawFlowerCrown == false)
                            OnClickBooleans.drawFlowerCrown = true;
                        else
                            OnClickBooleans.drawFlowerCrown = false;
                    }
                    if(position == 5) {
                        if(OnClickBooleans.drawGlassesPlus1 == false)
                            OnClickBooleans.drawGlassesPlus1 = true;
                        else
                            OnClickBooleans.drawGlassesPlus1 = false;
                    }
                    if(position == 6) {
                        if(OnClickBooleans.drawBeard1 == false)
                            OnClickBooleans.drawBeard1 = true;
                        else
                            OnClickBooleans.drawBeard1 = false;
                    }
                    if(position == 7) {
                        if(OnClickBooleans.drawKingCrown == false)
                            OnClickBooleans.drawKingCrown = true;
                        else
                            OnClickBooleans.drawKingCrown = false;
                    }
                    if(position == 8) {
                        if(OnClickBooleans.drawPigNose == false)
                            OnClickBooleans.drawPigNose = true;
                        else
                            OnClickBooleans.drawPigNose = false;
                    }
                    if(position == 9) {
                        if(OnClickBooleans.drawBatmanMask == false)
                            OnClickBooleans.drawBatmanMask = true;
                        else
                            OnClickBooleans.drawBatmanMask = false;
                    }
                    if(position == 10) {
                        if(OnClickBooleans.drawHalo == false)
                            OnClickBooleans.drawHalo = true;
                        else
                            OnClickBooleans.drawHalo = false;
                    }
                    if(position == 11) {
                        if(OnClickBooleans.drawGenji== false)
                            OnClickBooleans.drawGenji = true;
                        else
                            OnClickBooleans.drawGenji = false;
                    }
                }
            };
            setClickListener(clickEvent);
        }

        @Override
        public void onClick(View view) {
//            Log.e("OnClick", "" + mClickListener);
            if (mClickListener != null)  {
                mClickListener.onItemClick(view, getAdapterPosition());
            }
        }
    }

    // convenience method for getting data at click position
    public Bitmap getItem(int id) {
        return mBitmap.get(id);
    }

    // allows clicks events to be caught
    public void setClickListener(ItemClickListener itemClickListener) {
        this.mClickListener = itemClickListener;
    }

    // parent activity will implement this method to respond to click events
    public interface ItemClickListener {
        void onItemClick(View view, int position);
    }
}
