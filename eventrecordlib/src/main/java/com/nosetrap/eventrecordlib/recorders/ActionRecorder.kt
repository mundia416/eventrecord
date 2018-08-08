package com.nosetrap.eventrecordlib.recorders

import android.content.ContentValues
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.provider.BaseColumns
import android.util.Log
import com.nosetrap.eventrecordlib.RecorderManager
import com.nosetrap.eventrecordlib.OnActionTriggerListener
import com.nosetrap.eventrecordlib.RecorderCallback
import com.nosetrap.storage.sql.CursorCallback
import com.nosetrap.storage.sql.EasyCursor
import com.nosetrap.storage.sql.OrderBy

class ActionRecorder(context: Context) : BaseRecorder(context) {

    private val tableName: String

    /**
     * column that holds a boolean of when an action is triggered
     */
    private val colTrigger = "trigger"
    private val colDuration = "duration"

    init {
        val recorderManager = RecorderManager.getInstance(context)
        tableName = "action_record_data_${recorderManager.getActiveActionRecorderCount()}"
        recorderManager.actionRecorderCreated(tableName)
        databaseHandler.createTable(tableName, arrayOf(colTrigger,colDuration), null)
        setTableName(tableName)
    }

    /**
     * when this is called,an initial trigger is put in the database and the duration
     * is recorded between when the next trigger is recorder, the loop continues
     * @param reportStartRecording should the callback method for onRecordingStarted be called
     * @param reportPlayback should the callback method for playback be called
     */
    override fun startRecording(reportPlayback: Boolean,reportStartRecording: Boolean) {
        triggerValues = ArrayList()
        elapsedTimeValues = ArrayList()
        super.startRecording(reportPlayback,reportStartRecording)
    }

    /**
     * @param reportStopRecording should the callback method for onRecordingStopped be called
     */
    override fun stopRecording(reportStopRecording: Boolean) {
        super.stopRecording(reportStopRecording)
        storeRecordingInDatabase(reportStopRecording)
    }

    //the values for the table columns
    private var triggerValues = ArrayList<Boolean>()
    private var elapsedTimeValues = ArrayList<Long>()



    /**
     * data is stored in arraylists and then when playback stops it is put in the sqlDatabase
     * @param reportStopRecording should the callback method for onRecordingStopped be called
     */
    private fun storeRecordingInDatabase(reportStopRecording: Boolean){
        //the key used for the message sent to the recordingProgressHandler
        val keyProgress = "progress_key"

        val recordingSavedHandler = Handler(Handler.Callback {
            if(reportStopRecording) {
                recorderCallback?.onRecordingSaved()
            }
            true
        })

        val recordingProgressHandler = Handler(Handler.Callback {msg ->
            val percentageProgress = msg.data.getDouble(keyProgress,0.0)
            if(reportStopRecording) {
                recorderCallback?.onRecordingSaveProgress(percentageProgress)
            }
            true
        })



        Thread(Runnable {
        for(i in 0..(triggerValues.size-1)){
            val values = ContentValues()
            values.put(colTrigger,triggerValues[i])
            values.put(colDuration,elapsedTimeValues[i])
            databaseHandler.insert(tableName,values)

            //setting the save progress
            val c: Double = (i.toDouble()/triggerValues.size.toDouble())
            val percentageProgress: Double = c * 100

            val msg = Message()
            val data = Bundle()
            data.putDouble(keyProgress,percentageProgress)
            msg.data = data
            recordingProgressHandler.sendMessage(msg)

        }
            recordingSavedHandler.sendEmptyMessage(0)
    }).start()


    }

    /**
     * call when an action is performed in order to record it as a trigger
     */
    fun actionPerformed(): Boolean {
        return if (isInRecordMode) {
           updateElapsedTime()
            triggerValues.add(true)

            true
        } else {
            false
        }
    }


    /**
     * update the elapsed time column for the last entry in the table
     */
    private fun updateElapsedTime(){
        val elapsedTime = calculateElapsedTime()
        elapsedTimeValues.add(elapsedTime)
        resetElapsedTime()
    }

    /**
     * @param reportStopRecording should the callback method for onRecordingStopped be called
     * @param reportPlayback should the callback method for playback be called
     *
     */
    fun startPlayback(onActionTriggerListener: OnActionTriggerListener,reportStopRecording: Boolean = false,
                      reportPlayback: Boolean = true){
        isInPlayBackMode = true

        // ensures that recording is turned off
        if(isInRecordMode) {
            stopRecording(reportStopRecording)
        }

        if(reportPlayback) {
            recorderCallback?.onPrePlayback()
        }

        //the handler for the background thread
        val handler = Handler(Handler.Callback {

            if (isInPlayBackMode) {
                onActionTriggerListener.onTrigger()
            }

            true
        })

        val playbackStartedHandler = Handler(Handler.Callback {
            recorderCallback?.onPlaybackStarted()
            true
        })


        val triggers = ArrayList<Long>()

        //querring the database and looping through the data is all done in a background thread
        Thread(Runnable {
        databaseHandler.getAll(object : CursorCallback {
            override fun onCursorQueried(cursor: EasyCursor) {
                cursor.moveToFirst()

                for (i in cursor.getCount() downTo 1) {
                   // val trigger = cursor.getString(colTrigger)
                    val duration = cursor.getString(colDuration)

                    triggers.add(duration.toLong())

                    cursor.moveToNext()
                }

                if(reportPlayback) {
                 playbackStartedHandler.sendEmptyMessage(0)
                }

                //used in the loop in the thread
                var currentMoveIndex = 0

                //loop through the data in a background thread
                    while (isInPlayBackMode) {
                        try {

                            //looping to delay by the specified number of milliseconds
                            val initialTime = System.currentTimeMillis();
                            val expectedTime = initialTime + triggers[currentMoveIndex]

                            while (System.currentTimeMillis() <= expectedTime) {
                                //do nothing
                            }

                            if (currentMoveIndex == (triggers.size - 1)) {
                                //when its on the last pointer move then restart the moves starting from the first move
                                currentMoveIndex = 0
                            } else {
                                //go to next pointer move
                                currentMoveIndex++
                            }

                            handler.sendEmptyMessage(0)
                        } catch (e: Exception) {
                            onActionTriggerListener?.onError(e)
                        }
                    }
                }
            }, tableName)
        }).start()

    }
}