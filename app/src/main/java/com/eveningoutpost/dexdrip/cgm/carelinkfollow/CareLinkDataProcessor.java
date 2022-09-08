package com.eveningoutpost.dexdrip.cgm.carelinkfollow;

import com.eveningoutpost.dexdrip.Home;
import com.eveningoutpost.dexdrip.Models.BgReading;
import com.eveningoutpost.dexdrip.Models.BloodTest;
import com.eveningoutpost.dexdrip.Models.Sensor;
import com.eveningoutpost.dexdrip.Models.Treatments;
import com.eveningoutpost.dexdrip.Models.UserError;
import com.eveningoutpost.dexdrip.UtilityModels.Inevitable;
import com.eveningoutpost.dexdrip.UtilityModels.Pref;
import com.eveningoutpost.dexdrip.cgm.carelinkfollow.message.ConnectData;
import com.eveningoutpost.dexdrip.cgm.carelinkfollow.message.Marker;
import com.eveningoutpost.dexdrip.cgm.carelinkfollow.message.RecentData;
import com.eveningoutpost.dexdrip.cgm.carelinkfollow.message.SensorGlucose;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static com.eveningoutpost.dexdrip.Models.BgReading.SPECIAL_FOLLOWER_PLACEHOLDER;
import static com.eveningoutpost.dexdrip.Models.Treatments.pushTreatmentSyncToWatch;


/**
 * Medtronic CareLink Connect Data Processor
 *   - process CareLink data and convert to xDrip internal data
 *   - update xDrip internal data
 */
public class CareLinkDataProcessor {


    private static final String TAG = "ConnectFollowDP";
    private static final boolean D = false;

    private static final String SOURCE_CARELINK_FOLLOW = "CareLink Follow";


    static synchronized void processData(final RecentData recentData, final boolean live) {

        List<SensorGlucose> filteredSgList;
        List<Marker> filteredMarkerList;

        UserError.Log.d(TAG, "Start processsing data...");

        //SKIP ALL IF EMPTY!!!
        if (recentData == null) {
            UserError.Log.e(TAG, "Recent data is null, processing stopped!");
            return;
        }

        if (recentData.sgs == null) UserError.Log.d(TAG, "SGs is null!");

        //SKIP DATA processing if NO PUMP CONNECTION (time shift seems to be different in this case, needs further analysis)
        if (recentData.isNGP() && !recentData.pumpCommunicationState) {
            UserError.Log.d(TAG, "Not connected to pump => time can be wrong, leave processing!");
            return;
        }

        //SENSOR GLUCOSE (if available)
        if (recentData.sgs != null) {

            final BgReading lastBg = BgReading.lastNoSenssor();
            final long lastBgTimestamp = lastBg != null ? lastBg.timestamp : 0;

            //create filtered sortable SG list
            filteredSgList = new ArrayList<>();
            for (SensorGlucose sg : recentData.sgs) {
                //SG DateTime is null (sensor expired?)
                if (sg != null && sg.datetimeAsDate != null) {
                        filteredSgList.add(sg);
                }
            }

            if(filteredSgList.size() > 0) {

                final Sensor sensor = Sensor.createDefaultIfMissing();
                sensor.save();

                // place in order of oldest first
                Collections.sort(recentData.sgs, (o1, o2) -> o1.datetimeAsDate.compareTo(o2.datetimeAsDate));

                for (final SensorGlucose sg : filteredSgList) {

                    //Not NULL SG (shouldn't happen?!)
                    if (sg != null) {

                        //Not NULL DATETIME (sensorchange?)
                        if (sg.datetimeAsDate != null) {

                            //Not EPOCH 0 (warmup?)
                            if (sg.datetimeAsDate.getTime() > 1) {

                                //Not 0 SG (not calibrated?)
                                if (sg.sg > 0) {

                                    //newer than last BG
                                    if (sg.datetimeAsDate.getTime() > lastBgTimestamp) {

                                        if (sg.datetimeAsDate.getTime() > 0) {

                                            final BgReading existing = BgReading.getForPreciseTimestamp(sg.datetimeAsDate.getTime(), 10_000);
                                            if (existing == null) {
                                                UserError.Log.d(TAG, "NEW NEW NEW New entry: " + sg.toS());

                                                if (live) {
                                                    final BgReading bg = new BgReading();
                                                    bg.timestamp = sg.datetimeAsDate.getTime();
                                                    bg.calculated_value = (double) sg.sg;
                                                    bg.raw_data = SPECIAL_FOLLOWER_PLACEHOLDER;
                                                    bg.filtered_data = (double) sg.sg;
                                                    bg.noise = "";
                                                    bg.uuid = UUID.randomUUID().toString();
                                                    bg.calculated_value_slope = 0;
                                                    bg.sensor = sensor;
                                                    bg.sensor_uuid = sensor.uuid;
                                                    bg.source_info = SOURCE_CARELINK_FOLLOW;
                                                    bg.save();
                                                    bg.find_slope();
                                                    Inevitable.task("entry-proc-post-pr", 500, () -> bg.postProcess(false));
                                                }
                                            } else {
                                                //existing entry, not needed
                                            }
                                        } else {
                                            UserError.Log.e(TAG, "Could not parse a timestamp from: " + sg.toS());
                                        }
                                    }

                                } else {
                                    UserError.Log.d(TAG, "SG is 0 (calibration missed?)");
                                }

                            } else {
                                UserError.Log.d(TAG, "SG DateTime is 0 (warmup phase?)");
                            }

                        } else {
                            UserError.Log.d(TAG, "SG DateTime is null (sensor expired?)");
                        }

                    } else {
                        UserError.Log.d(TAG, "SG Entry is null!!!");
                    }
                }
            }
        }


        //MARKERS (if available)
        if (recentData.markers != null) {

            //Filter, correct markers
            filteredMarkerList = new ArrayList<>();
            for (Marker marker : recentData.markers) {
                if (marker != null) {
                    if (marker.type != null) {
                        //Try to determine correct date/time
                        try {
                            if (marker.dateTime == null)
                                marker.dateTime = calcTimeByIndex(recentData.dLastSensorTime, marker.index, true);
                        } catch (Exception ex) {
                            UserError.Log.d(TAG, "Time calculation error!");
                            continue;
                        }
                        //Add filtered marker ith correct date/time
                        if (marker.dateTime != null)
                            filteredMarkerList.add(marker);
                    }
                }
            }

            if(filteredMarkerList.size() > 0) {
                //sort markers by time
                Collections.sort(filteredMarkerList, (o1, o2) -> o1.dateTime.compareTo(o2.dateTime));

                //process markers one-by-one
                for (Marker marker : filteredMarkerList) {

                    //FINGER BG
                    if (marker.isBloodGlucose() && Pref.getBooleanDefaultFalse("clfollow_download_finger_bgs")) {
                        //check required values
                        if (marker.value != null && !marker.value.equals(0)) {
                            //new blood test
                            if (BloodTest.getForPreciseTimestamp(marker.dateTime.getTime(), 10000) == null) {
                                BloodTest.create(marker.dateTime.getTime(), marker.value, SOURCE_CARELINK_FOLLOW);
                            }
                        }

                    //INSULIN, MEAL => Treatment
                    } else if ((marker.type.equals(Marker.MARKER_TYPE_INSULIN) && Pref.getBooleanDefaultFalse("clfollow_download_boluses"))
                            || (marker.type.equals(Marker.MARKER_TYPE_MEAL) && Pref.getBooleanDefaultFalse("clfollow_download_meals"))) {

                        //insulin, meal only for pumps (not value in case of GC)
                        if (recentData.isNGP()) {

                            final Treatments t;
                            double carbs = 0;
                            double insulin = 0;

                            //Extract treament infos (carbs, insulin)
                            //Insulin
                            if (marker.type.equals(Marker.MARKER_TYPE_INSULIN)) {
                                carbs = 0;
                                if (marker.deliveredExtendedAmount != null && marker.deliveredFastAmount != null) {
                                    insulin = marker.deliveredExtendedAmount + marker.deliveredFastAmount;
                                }
                                //SKIP if insulin = 0
                                if (insulin == 0) continue;
                            //Carbs
                            } else if (marker.type.equals(Marker.MARKER_TYPE_MEAL)) {
                                if (marker.amount != null) {
                                    carbs = marker.amount;
                                }
                                insulin = 0;
                                //SKIP if carbs = 0
                                if (carbs == 0) continue;
                            }

                            //new Treatment
                            if (newTreatment(carbs, insulin, marker.dateTime.getTime())) {
                                t = Treatments.create(carbs, insulin, marker.dateTime.getTime());
                                if (t != null) {
                                    t.enteredBy = SOURCE_CARELINK_FOLLOW;
                                    t.save();
                                    if (Home.get_show_wear_treatments())
                                        pushTreatmentSyncToWatch(t, true);
                                }
                            }
                        }

                    }

                }
            }
        }

    }

    //Calculate DateTime using graph index (1 index = 5 minute)
    protected static Date calcTimeByIndex(Date lastSensorTime, int index, boolean round){
        if(lastSensorTime == null)
            return null;
        else if(round)
            //round to 10 minutes
            return new Date((Math.round((calcTimeByIndex(lastSensorTime,index,false).getTime()) / 600_000D) * 600_000L));
        else
            return new Date((lastSensorTime.getTime() - ((287 - index) * 300_000L)));
    }

    //Check if treatment is new (no identical entry (timestamp, carbs, insulin) exists)
    protected static boolean newTreatment(double carbs, double insulin, long timestamp){

        List<Treatments> treatmentsList;
        //Treatment with same timestamp and carbs + insulin exists?
        treatmentsList = Treatments.listByTimestamp(timestamp);
        if(treatmentsList != null) {
            for (Treatments treatments : treatmentsList) {
                if (treatments.carbs == carbs && treatments.insulin == insulin)
                    return  false;
            }
        }
        return  true;
    }

}