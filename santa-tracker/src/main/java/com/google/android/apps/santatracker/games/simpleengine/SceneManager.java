/*
 * Copyright (C) 2015 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.apps.santatracker.games.simpleengine;

import android.app.Activity;
import android.content.Context;
import android.graphics.PointF;
import android.util.SparseArray;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.lang.ref.WeakReference;
import java.util.ArrayList;

public class SceneManager {

    private static SceneManager instance = new SceneManager();
    private Renderer mRenderer = new Renderer();
    private SoundManager mSoundManager = null;
    private Scene mCurScene = null;
    private Scene mNewScene = null;
    private long mLastFrameTime = -1;
    private boolean mHasGL = false;
    private Context mAppContext = null;

    // reference to Activity, if it's in the resumed state -- otherwise null
    private WeakReference<Activity> mActivity = new WeakReference<Activity>(null);
    private boolean mActivityResumed = false;
    private boolean mActivityHasFocus = false;

    // queue of MotionEvents to process from the game thread
    private ArrayList<OurMotionEvent> mMotionEventQueue = new ArrayList<OurMotionEvent>(32);
    private ArrayList<OurMotionEvent> mMotionEventRecycle = new ArrayList<OurMotionEvent>(32);

    // this flag is raised by the UI thread when it adds something to mMotionEventQueue
    // and lowered by the game thread when it processes the motion event queue. This flag
    // should only be modified when mMotionEventQueue is locked; it can be read without
    // locking.
    private volatile boolean mCheckMotionEvents = false;

    // last x, y of pointer, keyed by pointer ID
    private SparseArray<PointF> mLastTouchCoords = new SparseArray<PointF>();

    // recycle bin of PointF objects
    private ArrayList<PointF> mPointRecycleBin = new ArrayList<PointF>();

    private SceneManager() {
    }

    public static SceneManager getInstance() {
        return instance;
    }

    void onGLSurfaceCreated(Context ctx) {
        mHasGL = true;
        mAppContext = ctx.getApplicationContext();
        mRenderer.onGLSurfaceCreated(mAppContext);
        if (mSoundManager == null) {
            mSoundManager = new SoundManager(ctx);
        }
    }

    void onGLSurfaceChanged(int width, int height) {
        mRenderer.onGLSurfaceChanged(width, height);
        if (mCurScene != null) {
            mCurScene.onScreenResized(width, height);
        }
    }

    private void installNewScene() {
        if (mCurScene != null) {
            mCurScene.onUninstall();
            mRenderer.reset();
            mSoundManager.reset();
        }
        mCurScene = mNewScene;
        mNewScene = null;
        if (mCurScene != null) {
            mCurScene.onInstall();
            mRenderer.startLoadingTexs(mAppContext);
        }
    }

    public void onPause() {
        mActivityResumed = false;
        if (mSoundManager != null) {
            mSoundManager.pause();
        }
        mActivity.clear();
    }

    public void onResume(Activity activity) {
        mActivityResumed = true;
        mActivity = new WeakReference<Activity>(activity);
        if (mSoundManager != null && mActivityHasFocus) {
            mSoundManager.resume();
        }
    }

    public Activity getActivity() {
        return mActivity.get();
    }

    public void onFocusChanged(boolean focus) {
        mActivityHasFocus = focus;
        if (!focus) {
            mSoundManager.pause();
        } else if (mActivityResumed && mSoundManager != null) {
            mSoundManager.resume();
        }
    }

    public boolean shouldBePlaying() {
        return mActivityResumed && mActivityHasFocus;
    }

    public Scene getCurrentScene() {
        return mCurScene;
    }

    void onDrawFrame() {
        if (!mHasGL) {
            Logger.w("Ignoring request to do frame without a GL surface.");
            return;
        }
        if (mNewScene != null) {
            installNewScene();
        }
        if (mCurScene != null) {
            if (mLastFrameTime < 0) {
                mLastFrameTime = System.currentTimeMillis();
            }
            float deltaT = (System.currentTimeMillis() - mLastFrameTime) * 0.001f;
            mLastFrameTime = System.currentTimeMillis();
            if (mRenderer.prepareFrame() && mSoundManager.isReady()) {
                mCurScene.doFrame(deltaT);
            } else {
                mCurScene.doStandbyFrame(deltaT);
            }
        }
        mRenderer.doFrame();

        // process touch events
        if (mCheckMotionEvents) {
            processMotionEvents();
        }
    }

    public void enableDebugLog(boolean enable) {
        Logger.enableDebugLog(enable);
    }

    public void requestNewScene(Scene c) {
        mNewScene = c;
    }

    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            return false;
        } else {
            processKeyEvent(keyCode, event);
            return true;
        }
    }

    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            return false;
        } else {
            processKeyEvent(keyCode, event);
            return true;
        }
    }

    public boolean onTouchEvent(MotionEvent event) {
        // we are running on the UI thread, so deliver the event to the queue,
        // where the game thread will pick it up to process
        synchronized (mMotionEventQueue) {
            int action = event.getActionMasked();

            // get updates about each pointer in the gesture
            int i;
            for (i = 0; i < event.getPointerCount(); i++) {
                int pointerId = event.getPointerId(i);
                float x = event.getX(i);
                float y = event.getY(i);

                // figure out delta from last touch event
                float deltaX = x - getLastTouchX(pointerId, x);
                float deltaY = y - getLastTouchY(pointerId, y);

                // queue the motion event
                queueMotionEvent(MotionEvent.ACTION_MOVE, pointerId, x, y, deltaX, deltaY);

                // update last touch coordinates
                setLastTouchCoords(pointerId, x, y);
            }

            // figure out if a pointer went up or down
            int id;
            PointF point;
            switch (action) {
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_POINTER_UP:
                    id = event.getPointerId(event.getActionIndex());
                    forgetLastTouchCoords(id);
                    queueMotionEvent(MotionEvent.ACTION_UP, id, event.getX(), event.getY(),
                            0.0f, 0.0f);
                    break;
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN:
                    id = event.getPointerId(event.getActionIndex());
                    setLastTouchCoords(id, event.getX(), event.getY());
                    queueMotionEvent(MotionEvent.ACTION_DOWN, id, event.getX(), event.getY(),
                            0.0f, 0.0f);
                    break;
            }
        }
        return true;
    }

    private float getLastTouchX(int pointerId, float defaultX) {
        PointF pt = mLastTouchCoords.get(pointerId, null);
        return pt != null ? pt.x : defaultX;
    }

    private float getLastTouchY(int pointerId, float defaultY) {
        PointF pt = mLastTouchCoords.get(pointerId, null);
        return pt != null ? pt.y : defaultY;
    }

    private void setLastTouchCoords(int pointerId, float x, float y) {
        PointF pt = mLastTouchCoords.get(pointerId, null);
        if (pt == null) {
            pt = allocPointF();
        }
        pt.x = x;
        pt.y = y;
        mLastTouchCoords.put(pointerId, pt);
    }

    private void forgetLastTouchCoords(int pointerId) {
        PointF pt = mLastTouchCoords.get(pointerId, null);
        if (pt != null) {
            mLastTouchCoords.remove(pointerId);
            recyclePointF(pt);
        }
    }

    private PointF allocPointF() {
        if (mPointRecycleBin.size() > 0) {
            PointF p = mPointRecycleBin.remove(mPointRecycleBin.size() - 1);
            p.x = p.y = 0.0f;
            return p;
        }
        return new PointF();
    }

    private void recyclePointF(PointF p) {
        mPointRecycleBin.add(p);
    }

    private void queueMotionEvent(int action, int pointerId, float screenX, float screenY,
            float deltaX, float deltaY) {
        OurMotionEvent e = mMotionEventRecycle.size() > 0 ?
                mMotionEventRecycle.remove(mMotionEventRecycle.size() - 1) :
                new OurMotionEvent();
        e.action = action;
        e.pointerId = pointerId;
        e.screenX = screenX;
        e.screenY = screenY;
        e.deltaX = deltaX;
        e.deltaY = deltaY;
        mMotionEventQueue.add(e);
        mCheckMotionEvents = true;
    }

    public Renderer getRenderer() {
        return mRenderer;
    }

    public SoundManager getSoundManager() {
        return mSoundManager;
    }

    ArrayList<OurMotionEvent> mTmpMotionEvent = new ArrayList<OurMotionEvent>(32);

    private void processMotionEvents() {
        int i;

        // move array items to our temporary array so we can unlock the original
        synchronized (mMotionEventQueue) {
            for (i = 0; i < mMotionEventQueue.size(); i++) {
                mTmpMotionEvent.add(mMotionEventQueue.get(i));
            }
            mMotionEventQueue.clear();
            mCheckMotionEvents = false;
        }

        // process the motion events
        for (i = 0; i < mTmpMotionEvent.size(); i++) {
            processMotionEvent(mTmpMotionEvent.get(i));
        }

        // recycle the objects
        synchronized (mMotionEventQueue) {
            for (i = 0; i < mTmpMotionEvent.size(); i++) {
                mMotionEventRecycle.add(mTmpMotionEvent.get(i));
            }
            mTmpMotionEvent.clear();
        }
    }

    private void processMotionEvent(OurMotionEvent event) {
        if (mCurScene == null) {
            return;
        }

        // convert the screen coordinates to our standard coordinate system
        float x = mRenderer.convertScreenX(event.screenX);
        float y = mRenderer.convertScreenY(event.screenY);
        float deltaX = mRenderer.convertScreenDeltaX(event.deltaX);
        float deltaY = mRenderer.convertScreenDeltaY(event.deltaY);

        switch (event.action) {
            case MotionEvent.ACTION_DOWN:
                mCurScene.onPointerDown(event.pointerId, x, y);
                break;
            case MotionEvent.ACTION_MOVE:
                mCurScene.onPointerMove(event.pointerId, x, y, deltaX, deltaY);
                break;
            case MotionEvent.ACTION_UP:
                mCurScene.onPointerUp(event.pointerId, x, y);
                break;
        }
    }

    private void processKeyEvent(int keyCode, KeyEvent event) {
        if (mCurScene == null) {
            return;
        }

        // convert the screen coordinates to our standard coordinate system

        switch (event.getAction()) {
            case KeyEvent.ACTION_DOWN:
                mCurScene.onKeyDown(keyCode, event.getRepeatCount());
                break;
            case KeyEvent.ACTION_UP:
                mCurScene.onKeyUp(keyCode);
                break;
        }
    }

    private class OurMotionEvent {

        int action;
        int pointerId;
        float screenX, screenY;
        float deltaX, deltaY;
    }
}
