/*
 * Copyright 2014-2015 pushbit <pushbit@gmail.com>
 *
 * This file is part of Dining Out.
 *
 * Dining Out is free software: you can redistribute it and/or modify it under the terms of the GNU
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * Dining Out is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with Dining Out. If not,
 * see <http://www.gnu.org/licenses/>.
 */

package net.sf.diningout.app.ui;

import android.app.LoaderManager.LoaderCallbacks;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.CursorLoader;
import android.content.Intent;
import android.content.Loader;
import android.database.Cursor;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.GridView;

import com.google.android.gms.ads.AdListener;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.InterstitialAd;

import net.sf.diningout.R;
import net.sf.diningout.app.RestaurantService;
import net.sf.diningout.provider.Contract.Restaurants;
import net.sf.sprockets.content.Content.Query;
import net.sf.sprockets.database.Cursors;
import net.sf.sprockets.database.EasyCursor;
import net.sf.sprockets.google.Place;
import net.sf.sprockets.google.Place.Prediction;
import net.sf.sprockets.net.Uris;
import net.sf.sprockets.sql.SQLite;

import icepick.Icicle;

import static android.provider.BaseColumns._ID;
import static net.sf.diningout.app.ui.RestaurantActivity.EXTRA_ID;
import static net.sf.diningout.data.Status.ACTIVE;
import static net.sf.sprockets.app.SprocketsApplication.cr;
import static net.sf.sprockets.gms.analytics.Trackers.event;

/**
 * Displays restaurant autocomplete and nearby restaurants or restaurant search results.
 */
public class RestaurantAddActivity extends BaseNavigationDrawerActivity
        implements LoaderCallbacks<Cursor>, RestaurantAutocompleteFragment.Listener,
        RestaurantsNearbyFragment.Listener {
    @Icicle
    String mPlaceId;

    @Icicle
    String mName;

    @Icicle
    String mSource;

    private InterstitialAd mInterstitialAd;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (mName != null) {
            setTitle(getString(R.string.add_s, mName));
        }
        setContentView(R.layout.restaurant_add);
        getLoaderManager().initLoader(0, null, this);

        mInterstitialAd = new InterstitialAd(this);
        mInterstitialAd.setAdUnitId("ca-app-pub-6887589184636373/8218103247");
        AdRequest adRequestInterstial = new AdRequest.Builder()
                .addTestDevice("E9DEB34031182776A4E765DCEF19F10D")
                .build();
        mInterstitialAd.loadAd(adRequestInterstial);

//listner for adClosed
        mInterstitialAd.setAdListener(new AdListener() {
            @Override
            public void onAdClosed() {
                AdRequest adRequest = new AdRequest.Builder()
                        .addTestDevice("E9DEB34031182776A4E765DCEF19F10D")
                        .build();
                mInterstitialAd.loadAd(adRequest);
            }
        });

    }

    @Override
    public Loader<Cursor> onCreateLoader(int id, Bundle args) {
        String[] proj = {Restaurants.PLACE_ID};
        String sel = Restaurants.PLACE_ID + " IS NOT NULL AND " + Restaurants.STATUS_ID + " = ?";
        String[] selArgs = {String.valueOf(ACTIVE.id)};
        return new CursorLoader(this, Restaurants.CONTENT_URI, proj, sel, selArgs, null);
    }

    @Override
    public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
        String[] ids = Cursors.allStrings(data, false);
        if (data.getCount() > 0) {
            autocomplete().mName.setPredictionFilter(Prediction.IdFilter.create().addIds(ids));
        }
        nearby().filter(Place.IdFilter.create().addIds(ids));
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        getMenuInflater().inflate(R.menu.restaurant_add, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.done:

                if (mPlaceId == null) {
                    return true;
                }
                /* check for existing Google place, update or insert new */
                EasyCursor c = new EasyCursor(this, Query.create().uri(Restaurants.CONTENT_URI)
                        .proj(_ID, Restaurants.STATUS_ID)
                        .sel(Restaurants.PLACE_ID + " = ?").args(mPlaceId));
                long id = 0L;
                if (c.moveToFirst()) {
                    id = c.getLong(_ID);
                }
                if (id <= 0) { // insert new
                    ContentValues vals = new ContentValues(4);
                    vals.put(Restaurants.NAME, mName);
                    vals.put(Restaurants.NORMALISED_NAME, SQLite.normalise(mName));
                    vals.put(Restaurants.PLACE_ID, mPlaceId);
                    vals.put(Restaurants.COLOR, Restaurants.defaultColor());
                    id = ContentUris.parseId(cr().insert(Restaurants.CONTENT_URI, vals));
                } else if (c.getInt(Restaurants.STATUS_ID) != ACTIVE.id) { // resurrect
                    ContentValues vals = new ContentValues(2);
                    vals.put(Restaurants.STATUS_ID, ACTIVE.id);
                    vals.put(Restaurants.DIRTY, 1);
                    cr().update(Uris.appendId(Restaurants.CONTENT_URI, c), vals, null, null);
                }
                c.close();
                if (id > 0) {
                    startActivity(
                            new Intent(this, RestaurantActivity.class).putExtra(EXTRA_ID, id));
                    startService(new Intent(this, RestaurantService.class)
                            .putExtra(RestaurantService.EXTRA_ID, id));
                    finish();
                    event("restaurant add", "chosen from", mSource);
                    mInterstitialAd.show();
                }
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onRestaurantNameChange(CharSequence name) {
        GridView grid = nearby().mGrid;
        if (TextUtils.getTrimmedLength(name) > 0) {
            if (grid.getCheckedItemCount() > 0) { // switch from list to autocomplete
                grid.setItemChecked(grid.getCheckedItemPosition(), false);
                clear();
            }
        } else if (grid.getCheckedItemCount() == 0) { // forget any autocompletion
            clear();
        }
    }

    @Override
    public void onRestaurantAutocomplete(Prediction place) {
        set(place.getPlaceId().getId(), place.getDescription(), "autocomplete");
    }

    @Override
    public void onRestaurantSearch(CharSequence name) {

        nearby().search(name.toString());
        event("restaurant add", "search");

    }

    @Override
    public void onRestaurantClick(Place place) {

        autocomplete().mName.setText(null);
        set(place.getPlaceId().getId(), place.getName(),
                TextUtils.isEmpty(nearby().mSearch) ? "nearby" : "search result");
    }

    private void set(String placeId, String name, String source) {
        mPlaceId = placeId;
        mName = name;
        mSource = source;
        setTitle(getString(R.string.add_s, mName));

    }

    private void clear() {

        mPlaceId = null;
        mName = null;
        mSource = null;
        setTitle(R.string.add_restaurant_title);

    }

    private RestaurantAutocompleteFragment autocomplete() {
        return (RestaurantAutocompleteFragment) getFragmentManager()
                .findFragmentById(R.id.autocomplete);
    }

    private RestaurantsNearbyFragment nearby() {
        return (RestaurantsNearbyFragment) getFragmentManager().findFragmentById(R.id.nearby);
    }

    @Override
    public void onLoaderReset(Loader<Cursor> loader) {
    }
}
