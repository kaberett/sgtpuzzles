package name.boyle.chris.sgtpuzzles;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.support.annotation.NonNull;
import android.support.v4.view.GestureDetectorCompat;
import android.support.v4.view.ScaleGestureDetectorCompat;
import android.support.v4.view.ViewCompat;
import android.support.v4.widget.EdgeEffectCompat;
import android.support.v4.widget.ScrollerCompat;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewConfiguration;

public class GameView extends View
{
	private GamePlay parent;
	private Bitmap bitmap;
	private Canvas canvas;
	private final Paint paint;
	private Paint checkerboardPaint;
	private final Bitmap[] blitters;
	int[] colours = new int[0];
	float density;
	int w, h, wDip, hDip;
	private final int longPressTimeout = ViewConfiguration.getLongPressTimeout();
	private String hardwareKeys;
	private enum TouchState { IDLE, WAITING_LONG_PRESS, DRAGGING, PINCH }
	private TouchState touchState = TouchState.IDLE;
	private int button;
	private int backgroundColour;
	private boolean waitingSpace = false;
	private PointF touchStart;
	private final double maxDistSq;
	static final int
			LEFT_BUTTON = 0x0200, MIDDLE_BUTTON = 0x201, RIGHT_BUTTON = 0x202,
			LEFT_DRAG = 0x203, //MIDDLE_DRAG = 0x204, RIGHT_DRAG = 0x205,
			LEFT_RELEASE = 0x206, MOD_CTRL = 0x1000,
			MOD_SHIFT = 0x2000, ALIGN_V_CENTRE = 0x100,
			ALIGN_H_CENTRE = 0x001, ALIGN_H_RIGHT = 0x002, TEXT_MONO = 0x10;
	private static final int DRAG = LEFT_DRAG - LEFT_BUTTON;  // not bit fields, but there's a pattern
    private static final int RELEASE = LEFT_RELEASE - LEFT_BUTTON;
	static final int CURSOR_UP = 0x209, CURSOR_DOWN = 0x20a,
			CURSOR_LEFT = 0x20b, CURSOR_RIGHT = 0x20c, MOD_NUM_KEYPAD = 0x4000;
	int keysHandled = 0;  // debug
	final boolean hasPinchZoom;
	ScaleGestureDetector scaleDetector = null;
	GestureDetectorCompat gestureDetector;
	private static final float MAX_ZOOM = 30.f;
	private static final float ZOOM_OVERDRAW_PROPORTION = 0.25f;  // of a screen-full, in each direction, that you can see before checkerboard
	final Point TEXTURE_SIZE_BEFORE_ICS = new Point(2048, 2048);
	private int overdrawX, overdrawY;
	private Matrix zoomMatrix = new Matrix(), zoomInProgressMatrix = new Matrix(),
			inverseZoomMatrix = new Matrix(), tempDrawMatrix = new Matrix();
	enum DragMode { UNMODIFIED, REVERT_OFF_SCREEN, REVERT_TO_START, PREVENT }
	private DragMode dragMode = DragMode.UNMODIFIED;
	private ScrollerCompat mScroller;
	private EdgeEffectCompat[] edges = new EdgeEffectCompat[4];
	// ARGB_8888 is viewable in Android Studio debugger but very memory-hungry
	// It's also necessary to work around a 4.1 bug https://github.com/chrisboyle/sgtpuzzles/issues/63
	private static final Bitmap.Config BITMAP_CONFIG =
			(Build.VERSION.SDK_INT == Build.VERSION_CODES.JELLY_BEAN)  // bug only seen on 4.1.x
					? Bitmap.Config.ARGB_4444 : Bitmap.Config.RGB_565;

	public GameView(Context context, AttributeSet attrs)
	{
		super(context, attrs);
		if (! isInEditMode())
			this.parent = (GamePlay)context;
		density = getResources().getDisplayMetrics().density;
		bitmap = Bitmap.createBitmap(100, 100, BITMAP_CONFIG);  // for safety
		canvas = new Canvas(bitmap);
		paint = new Paint();
		paint.setAntiAlias(true);
		paint.setStrokeCap(Paint.Cap.SQUARE);
		paint.setStrokeWidth(1.f);  // will be scaled with everything else as long as it's non-zero
		checkerboardPaint = new Paint();
		final Bitmap checkerboard = ((BitmapDrawable) getResources().getDrawable(R.drawable.checkerboard)).getBitmap();
		checkerboardPaint.setShader(new BitmapShader(checkerboard, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
		blitters = new Bitmap[512];
		maxDistSq = Math.pow(ViewConfiguration.get(context).getScaledTouchSlop(), 2);
		backgroundColour = getDefaultBackgroundColour();
		mScroller = ScrollerCompat.create(context);
		for (int i = 0; i < 4; i++) {
			edges[i] = new EdgeEffectCompat(context);
		}
		gestureDetector = new GestureDetectorCompat(getContext(), new GestureDetector.OnGestureListener() {
			@Override
			public boolean onDown(MotionEvent event) {
				int meta = event.getMetaState();
				int buttonState = 1; // MotionEvent.BUTTON_PRIMARY
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
					buttonState = event.getButtonState();
				}
				if ((meta & KeyEvent.META_ALT_ON) > 0  ||
						buttonState == 4 /* MotionEvent.BUTTON_TERTIARY */)  {
					button = MIDDLE_BUTTON;
				} else if ((meta & KeyEvent.META_SHIFT_ON) > 0  ||
						buttonState == 2 /* MotionEvent.BUTTON_SECONDARY */) {
					button = RIGHT_BUTTON;
				} else {
					button = LEFT_BUTTON;
				}
				touchStart = pointFromEvent(event);
				touchState = TouchState.WAITING_LONG_PRESS;
				parent.handler.removeCallbacks(sendLongPress);
				parent.handler.postDelayed(sendLongPress, longPressTimeout);
				return true;
			}

			@Override
			public boolean onScroll(MotionEvent downEvent, MotionEvent event, float distanceX, float distanceY) {
				// 2nd clause is 2 fingers a constant distance apart
				if ((hasPinchZoom && isScaleInProgress()) || event.getPointerCount() > 1) {
					revertDragInProgress(pointFromEvent(event));
					if (touchState == TouchState.WAITING_LONG_PRESS) {
						parent.handler.removeCallbacks(sendLongPress);
					}
					touchState = TouchState.PINCH;
					scrollBy(distanceX, distanceY);
					return true;
				}
				return false;
			}

			@Override public boolean onSingleTapUp(MotionEvent event) { return true; }
			@Override public void onShowPress(MotionEvent e) {}
			@Override public void onLongPress(MotionEvent e) {}

			@Override
			public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
				if (touchState != TouchState.PINCH) {  // require 2 fingers
					return false;
				}
				final float scale = getXScale(zoomMatrix) * getXScale(zoomInProgressMatrix);
				final PointF currentScroll = getCurrentScroll();
				final int xMax = Math.round(scale * w);
				final int yMax = Math.round(scale * h);
				final double rootScale = Math.sqrt(scale);  // seems about right, not really sure why
				mScroller.fling(Math.round(currentScroll.x), Math.round(currentScroll.y),
						(int)-Math.round(velocityX / rootScale), (int)-Math.round(velocityY / rootScale), 0, xMax, 0, yMax);
				animateScroll.run();
				return true;
			}
		});
		// We do our own long-press detection to capture movement afterwards
		gestureDetector.setIsLongpressEnabled(false);
		hasPinchZoom = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.FROYO);
		if (hasPinchZoom) {
			enablePinchZoom();
		}
	}

	private PointF getCurrentScroll() {
		return viewToGame(new PointF(w/2, h/2));
	}

	private Runnable animateScroll = new Runnable() {
		@Override
		public void run() {
			mScroller.computeScrollOffset();
			final PointF currentScroll = getCurrentScroll();
			scrollBy(mScroller.getCurrX() - currentScroll.x, mScroller.getCurrY() - currentScroll.y);
			if (mScroller.isFinished()) {
				ViewCompat.postOnAnimation(GameView.this, new Runnable() {
					@Override
					public void run() {
						redrawForZoomChange();
						for (EdgeEffectCompat edge : edges) edge.onRelease();
					}
				});
			} else {
				ViewCompat.postOnAnimation(GameView.this, animateScroll);
			}
		}
	};

	public void setDragModeFor(final String whichBackend) {
		final int modeId = getResources().getIdentifier(whichBackend + "_drag_mode", "string", getContext().getPackageName());
		final String mode;
		if (modeId <= 0 || ((mode = getResources().getString(modeId)) == null)) {
			dragMode = DragMode.UNMODIFIED;
			return;
		}
		if (mode.equals("off_screen")) dragMode = DragMode.REVERT_OFF_SCREEN;
		else if (mode.equals("start")) dragMode = DragMode.REVERT_TO_START;
		else if (mode.equals("prevent")) dragMode = DragMode.PREVENT;
		else dragMode = DragMode.UNMODIFIED;
	}

	private void revertDragInProgress(final PointF here) {
		if (touchState == TouchState.DRAGGING) {
			final PointF dragTo;
			switch (dragMode) {
				case REVERT_OFF_SCREEN: dragTo = new PointF(-1, -1); break;
				case REVERT_TO_START: dragTo = viewToGame(touchStart); break;
				default: dragTo = viewToGame(here); break;
			}
			parent.sendKey(dragTo, button + DRAG);
			parent.sendKey(dragTo, button + RELEASE);
		}
	}

	@Override
	public void scrollBy(int x, int y) {
		scrollBy((float)x, (float)y);
	}

	private void scrollBy(float distanceX, float distanceY) {
		zoomInProgressMatrix.postTranslate(-distanceX, -distanceY);
		zoomMatrixUpdated(true);
		ViewCompat.postInvalidateOnAnimation(GameView.this);
	}

	private void zoomMatrixUpdated(final boolean userAction) {
		// Constrain scrolling to game bounds
		invertZoomMatrix();  // needed for viewToGame
		final PointF topLeft = viewToGame(new PointF(0, 0));
		final PointF bottomRight = viewToGame(new PointF(w, h));
		if (topLeft.x < 0) {
			zoomInProgressMatrix.preTranslate(topLeft.x, 0);
			if (userAction) hitEdge(3, -topLeft.x);
		} else if (exceedsTouchSlop(topLeft.x)) {
			edges[3].onRelease();
		}
		if (bottomRight.x > wDip) {
			zoomInProgressMatrix.preTranslate(bottomRight.x - wDip, 0);
			if (userAction) hitEdge(1, bottomRight.x - wDip);
		} else if (exceedsTouchSlop(wDip - bottomRight.x)) {
			edges[1].onRelease();
		}
		if (topLeft.y < 0) {
			zoomInProgressMatrix.preTranslate(0, topLeft.y);
			if (userAction) hitEdge(0, -topLeft.y);
		} else if (exceedsTouchSlop(topLeft.y)) {
			edges[0].onRelease();
		}
		if (bottomRight.y > hDip) {
			zoomInProgressMatrix.preTranslate(0, bottomRight.y - hDip);
			if (userAction) hitEdge(2, bottomRight.y - hDip);
		} else if (exceedsTouchSlop(hDip - bottomRight.y)) {
			edges[2].onRelease();
		}
		canvas.setMatrix(zoomMatrix);
		invertZoomMatrix();  // now with our changes
	}

	private void hitEdge(int edge, float delta) {
		if (!mScroller.isFinished()) {
			edges[edge].onAbsorb(Math.round(mScroller.getCurrVelocity()));
			mScroller.abortAnimation();
		} else {
			edges[edge].onPull(delta);
		}
	}

	private boolean exceedsTouchSlop(float dist) {
		return Math.pow(dist, 2) > maxDistSq;
	}

	private boolean movedPastTouchSlop(float x, float y) {
		return Math.pow(Math.abs(x - touchStart.x), 2)
				+ Math.pow(Math.abs(y - touchStart.y) ,2)
				> maxDistSq;
	}

	@TargetApi(Build.VERSION_CODES.FROYO)
	private boolean isScaleInProgress() {
		return scaleDetector.isInProgress();
	}

	@TargetApi(Build.VERSION_CODES.FROYO)
	private void enablePinchZoom() {
		scaleDetector = new ScaleGestureDetector(getContext(), new ScaleGestureDetector.SimpleOnScaleGestureListener() {
			@Override
			public boolean onScale(ScaleGestureDetector detector) {
				float factor = detector.getScaleFactor();
				final float scale = getXScale(zoomMatrix) * getXScale(zoomInProgressMatrix);
				final float nextScale = scale * factor;
				final boolean wasZoomedOut = (scale == density);
				if (nextScale < density + 0.01f) {
					if (! wasZoomedOut) {
						resetZoomMatrix();
						redrawForZoomChange();
					}
				} else {
					if (nextScale > MAX_ZOOM) {
						factor = MAX_ZOOM / scale;
					}
					zoomInProgressMatrix.postScale(factor, factor,
							overdrawX + detector.getFocusX(),
							overdrawY + detector.getFocusY());
				}
				zoomMatrixUpdated(true);
				ViewCompat.postInvalidateOnAnimation(GameView.this);
				return true;
			}
		});
		ScaleGestureDetectorCompat.setQuickScaleEnabled(scaleDetector, false);
	}

	void resetZoomForClear() {
		resetZoomMatrix();
		canvas.setMatrix(zoomMatrix);
		invertZoomMatrix();
	}

	private void resetZoomMatrix() {
		zoomMatrix.reset();
		zoomMatrix.postTranslate(overdrawX, overdrawY);
		zoomMatrix.postScale(density, density,
				overdrawX, overdrawY);
		zoomInProgressMatrix.reset();
	}

	private void invertZoomMatrix() {
		final Matrix copy = new Matrix(zoomMatrix);
		copy.postConcat(zoomInProgressMatrix);
		copy.postTranslate(-overdrawX, -overdrawY);
		if (!copy.invert(inverseZoomMatrix)) {
			throw new RuntimeException("zoom not invertible");
		}
	}

	private void redrawForZoomChange() {
		if (getXScale(zoomMatrix) < 1.01f && getXScale(zoomInProgressMatrix) > 1.01f) {
			parent.zoomedIn();
		}
		zoomMatrixUpdated(false);  // constrains zoomInProgressMatrix
		zoomMatrix.postConcat(zoomInProgressMatrix);
		zoomInProgressMatrix.reset();
		canvas.setMatrix(zoomMatrix);
		invertZoomMatrix();
		if (parent != null) {
			clear();
			parent.gameViewResized();  // not just forceRedraw() - need to reallocate blitters
		}
		ViewCompat.postInvalidateOnAnimation(GameView.this);
	}

	private float getXScale(Matrix m) {
		float[] values = new float[9];
		m.getValues(values);
		return values[Matrix.MSCALE_X];
	}

	PointF pointFromEvent(MotionEvent event) {
		return new PointF(event.getX(), event.getY());
	}

	PointF viewToGame(PointF point) {
		float[] f = { point.x, point.y };
		inverseZoomMatrix.mapPoints(f);
		return new PointF(f[0], f[1]);
	}

	@TargetApi(Build.VERSION_CODES.FROYO)
	private boolean checkPinchZoom(MotionEvent event) {
		return scaleDetector.onTouchEvent(event);
	}

	private final Runnable sendLongPress = new Runnable() {
		public void run() {
			if (hasPinchZoom && isScaleInProgress()) return;
			button = RIGHT_BUTTON;
			touchState = TouchState.DRAGGING;
			performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
			parent.sendKey(viewToGame(touchStart), button);
		}
	};

	@Override
	public boolean onTouchEvent(@NonNull MotionEvent event)
	{
		if (parent.currentBackend == null) return false;
		boolean sdRet = hasPinchZoom && checkPinchZoom(event);
		boolean gdRet = gestureDetector.onTouchEvent(event);
		if (event.getAction() == MotionEvent.ACTION_UP) {
			parent.handler.removeCallbacks(sendLongPress);
			if (touchState == TouchState.PINCH && mScroller.isFinished()) {
				redrawForZoomChange();
				for (EdgeEffectCompat edge : edges) edge.onRelease();
			} else if (touchState == TouchState.WAITING_LONG_PRESS) {
				parent.sendKey(viewToGame(touchStart), button);
				touchState = TouchState.DRAGGING;
			}
			if (touchState == TouchState.DRAGGING) {
				parent.sendKey(viewToGame(pointFromEvent(event)), button + RELEASE);
			}
			touchState = TouchState.IDLE;
			return true;
		} else if (event.getAction() == MotionEvent.ACTION_MOVE) {
			// 2nd clause is 2 fingers a constant distance apart
			if ((hasPinchZoom && isScaleInProgress()) || event.getPointerCount() > 1) {
				return sdRet || gdRet;
			}
			float x = event.getX(), y = event.getY();
			if (touchState == TouchState.WAITING_LONG_PRESS && movedPastTouchSlop(x, y)) {
				parent.handler.removeCallbacks(sendLongPress);
				if (dragMode == DragMode.PREVENT) {
					touchState = TouchState.IDLE;
				} else {
					parent.sendKey(viewToGame(touchStart), button);
					touchState = TouchState.DRAGGING;
				}
			}
			if (touchState == TouchState.DRAGGING) {
				parent.sendKey(viewToGame(pointFromEvent(event)), button + DRAG);
				return true;
			}
			return false;
		} else {
			return sdRet || gdRet;
		}
	}

	private final Runnable sendSpace = new Runnable() {
		public void run() {
			waitingSpace = false;
			parent.sendKey(0, 0, ' ');
		}
	};

	@Override
	public boolean onKeyDown(int keyCode, @NonNull KeyEvent event)
	{
		int key = 0, repeat = event.getRepeatCount();
		switch( keyCode ) {
		case KeyEvent.KEYCODE_DPAD_UP:    key = CURSOR_UP;    break;
		case KeyEvent.KEYCODE_DPAD_DOWN:  key = CURSOR_DOWN;  break;
		case KeyEvent.KEYCODE_DPAD_LEFT:  key = CURSOR_LEFT;  break;
		case KeyEvent.KEYCODE_DPAD_RIGHT: key = CURSOR_RIGHT; break;
		// dpad center auto-repeats on at least Tattoo, Hero
		case KeyEvent.KEYCODE_DPAD_CENTER:
			if (repeat > 0) return false;
			if (event.isShiftPressed()) {
				key = ' ';
				break;
			}
			touchStart = new PointF(0, 0);
			waitingSpace = true;
			parent.handler.removeCallbacks( sendSpace );
			parent.handler.postDelayed( sendSpace, longPressTimeout);
			keysHandled++;
			return true;
		case KeyEvent.KEYCODE_ENTER: key = '\n'; break;
		case KeyEvent.KEYCODE_FOCUS: case KeyEvent.KEYCODE_SPACE: case KeyEvent.KEYCODE_BUTTON_X:
			key = ' '; break;
		case KeyEvent.KEYCODE_BUTTON_L1: key = 'U'; break;
		case KeyEvent.KEYCODE_BUTTON_R1: key = 'R'; break;
		case KeyEvent.KEYCODE_DEL: key = '\b'; break;
		}
		if (key == CURSOR_UP || key == CURSOR_DOWN || key == CURSOR_LEFT || key == CURSOR_RIGHT) {
			// "only apply to cursor keys"
			// http://www.chiark.greenend.org.uk/~sgtatham/puzzles/devel/backend.html#backend-interpret-move
			if( event.isShiftPressed() ) key |= MOD_SHIFT;
			if( event.isAltPressed() ) key |= MOD_CTRL;
		}
		// we probably don't want MOD_NUM_KEYPAD here (numbers are in a line on G1 at least)
		if (key == 0) {
			int exactKey = event.getUnicodeChar();
			if ((exactKey >= 'A' && exactKey <= 'Z') || hardwareKeys.indexOf(exactKey) >= 0) {
				key = exactKey;
			} else {
				key = event.getMatch(hardwareKeys.toCharArray());
				if (key == 0 && (exactKey == 'u' || exactKey == 'r')) key = exactKey;
			}
		}
		if( key == 0 ) return super.onKeyDown(keyCode, event);  // handles Back etc.
		parent.sendKey(0, 0, key);
		keysHandled++;
		return true;
	}

	public void setHardwareKeys(String hardwareKeys) {
		this.hardwareKeys = hardwareKeys;
	}

	@Override
	public boolean onKeyUp( int keyCode, KeyEvent event )
	{
		if (keyCode != KeyEvent.KEYCODE_DPAD_CENTER || ! waitingSpace)
			return super.onKeyUp(keyCode, event);
		parent.handler.removeCallbacks(sendSpace);
		parent.sendKey(0, 0, '\n');
		return true;
	}

	@Override
	protected void onDraw( Canvas c )
	{
		if( bitmap == null ) return;
		tempDrawMatrix.reset();
		tempDrawMatrix.preTranslate(-overdrawX, -overdrawY);
		tempDrawMatrix.preConcat(zoomInProgressMatrix);
		final int restore = c.save();
		c.concat(tempDrawMatrix);
		float[] f = { 0, 0, bitmap.getWidth(), bitmap.getHeight() };
		tempDrawMatrix.mapPoints(f);
		if (f[0] > 0 || f[1] < w || f[2] < 0 || f[3] > h) {
			c.drawPaint(checkerboardPaint);
		}
		c.drawBitmap(bitmap, 0, 0, null);
		c.restoreToCount(restore);
		boolean keepAnimating = false;
		for (int i = 0; i < 4; i++) {
			if (!edges[i].isFinished()) {
				keepAnimating = true;
				final int restoreTo = c.save();
				c.rotate(i * 90);
				if (i == 1) {
					c.translate(0, -w);
				} else if (i == 2) {
					c.translate(-w, -h);
				} else if (i == 3) {
					c.translate(-h, 0);
				}
				final boolean flip = (i % 2) > 0;
				edges[i].setSize(flip ? h : w, flip ? w : h);
				edges[i].draw(c);
				c.restoreToCount(restoreTo);
			}
		}
		if (keepAnimating) {
			ViewCompat.postInvalidateOnAnimation(this);
		}
	}

	@Override
	protected void onSizeChanged(int w, int h, int oldW, int oldH)
	{
		if( w <= 0 ) w = 1;
		if( h <= 0 ) h = 1;
		wDip = Math.round((float)w/density); hDip = Math.round((float)h/density);
		if( w <= 0 ) wDip = 1;
		if( h <= 0 ) hDip = 1;
		if (bitmap != null) bitmap.recycle();
		overdrawX = Math.round(ZOOM_OVERDRAW_PROPORTION * w);
		overdrawY = Math.round(ZOOM_OVERDRAW_PROPORTION * h);
		// texture size limit, see http://stackoverflow.com/a/7523221/6540
		final Point maxTextureSize =
				(Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH)
						? getMaxTextureSize() : TEXTURE_SIZE_BEFORE_ICS;
		// Assumes maxTextureSize >= (w,h) otherwise you get checkerboard edges
		// https://github.com/chrisboyle/sgtpuzzles/issues/199
		overdrawX = Math.min(overdrawX, (maxTextureSize.x - w) / 2);
		overdrawY = Math.min(overdrawY, (maxTextureSize.y - h) / 2);
		bitmap = Bitmap.createBitmap(w + 2 * overdrawX, h + 2 * overdrawY, BITMAP_CONFIG);
		clear();
		canvas = new Canvas(bitmap);
		this.w = w; this.h = h;
		redrawForZoomChange();
		if (parent != null) parent.gameViewResized();
		if (isInEditMode()) {
			// Draw a little placeholder to aid UI editing
			Drawable d = getResources().getDrawable(R.drawable.net);
			int s = w<h ? w : h;
			int mx = (w-s)/2, my = (h-s)/2;
			d.setBounds(new Rect(mx,my,mx+s,my+s));
			d.draw(canvas);
		}
	}

	@TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
	private Point getMaxTextureSize() {
		final int maxW = canvas.getMaximumBitmapWidth();
		final int maxH = canvas.getMaximumBitmapHeight();
		if (maxW < 2048 || maxH < 2048) {
			return new Point(maxW, maxH);
		}
		// maxW/maxH are otherwise likely a lie, and we should be careful of OOM risk anyway
		// https://github.com/chrisboyle/sgtpuzzles/issues/195
		final DisplayMetrics metrics = getResources().getDisplayMetrics();
		final int largestDimension = Math.max(metrics.widthPixels, metrics.heightPixels);
		return (largestDimension > 2048) ? new Point(4096, 4096) : new Point(2048, 2048);
	}

	public void clear()
	{
		bitmap.eraseColor(backgroundColour);
	}

	@Override
	public void setBackgroundColor(int colour) {
		super.setBackgroundColor(colour);
		backgroundColour = colour;
	}

	@UsedByJNI
	int getDefaultBackgroundColour() {
		return getResources().getColor(R.color.game_background);
	}

	@UsedByJNI
	void clipRect(int x, int y, int w, int h)
	{
		canvas.clipRect(new RectF(x - 0.5f, y - 0.5f, x + w - 0.5f, y + h - 0.5f), Region.Op.REPLACE);
	}

	@UsedByJNI
	void unClip(int marginX, int marginY)
	{
		canvas.clipRect(marginX - 0.5f, marginY - 0.5f, w - marginX - 1.5f, h - marginY - 1.5f, Region.Op.REPLACE);
	}

	@UsedByJNI
	void fillRect(final int x, final int y, final int w, final int h, final int colour)
	{
		paint.setColor(colours[colour]);
		paint.setStyle(Paint.Style.FILL);
		paint.setAntiAlias(false);  // required for regions in Map to look continuous (and by API)
		if (w == 1 && h == 1) {
			canvas.drawPoint(x, y, paint);
		} else if ((w == 1) ^ (h == 1)) {
			canvas.drawLine(x, y, x + w - 1, y + h - 1, paint);
		} else {
			canvas.drawRect(x - 0.5f, y - 0.5f, x + w - 0.5f, y + h - 0.5f, paint);
		}
		paint.setAntiAlias(true);
	}

	@UsedByJNI
	void drawLine(float thickness, float x1, float y1, float x2, float y2, int colour)
	{
		paint.setColor(colours[colour]);
		paint.setStrokeWidth(Math.max(thickness, 1.f));
		canvas.drawLine(x1, y1, x2, y2, paint);
		paint.setStrokeWidth(1.f);
	}

	@UsedByJNI
	void drawPoly(int[] points, int ox, int oy, int line, int fill)
	{
		Path path = new Path();
		path.moveTo(points[0] + ox, points[1] + oy);
		for(int i=1; i < points.length/2; i++) {
			path.lineTo(points[2 * i] + ox, points[2 * i + 1] + oy);
		}
		path.close();
		// cheat slightly: polygons up to square look prettier without (and adjacent squares want to
		// look continuous in lightup)
		boolean disableAntiAlias = points.length <= 8;  // 2 per point
		if (disableAntiAlias) paint.setAntiAlias(false);
		drawPoly(path, line, fill);
		paint.setAntiAlias(true);
	}

	private void drawPoly(Path p, int lineColour, int fillColour)
	{
		if (fillColour != -1) {
			paint.setColor(colours[fillColour]);
			paint.setStyle(Paint.Style.FILL);
			canvas.drawPath(p, paint);
		}
		paint.setColor(colours[lineColour]);
		paint.setStyle(Paint.Style.STROKE);
		canvas.drawPath(p, paint);
	}

	@UsedByJNI
	void drawCircle(float thickness, float x, float y, float r, int lineColour, int fillColour)
	{
		if (r <= 0.5f) fillColour = lineColour;
		r = Math.max(r, 0.4f);
		if (fillColour != -1) {
			paint.setColor(colours[fillColour]);
			paint.setStyle(Paint.Style.FILL);
			canvas.drawCircle(x, y, r, paint);
		}
		paint.setColor(colours[lineColour]);
		paint.setStyle(Paint.Style.STROKE);
		if (thickness > 1.f) {
			paint.setStrokeWidth(thickness);
		}
		canvas.drawCircle(x, y, r, paint);
		paint.setStrokeWidth(1.f);
	}

	@UsedByJNI
	void drawText(int x, int y, int flags, int size, int colour, String text)
	{
		paint.setColor(colours[colour]);
		paint.setStyle(Paint.Style.FILL);
		paint.setTypeface( (flags & TEXT_MONO) != 0 ? Typeface.MONOSPACE : Typeface.DEFAULT );
		paint.setTextSize(size);
		Paint.FontMetrics fm = paint.getFontMetrics();
		float asc = Math.abs(fm.ascent), desc = Math.abs(fm.descent);
		if ((flags & ALIGN_V_CENTRE) != 0) y += asc - (asc+desc)/2;
		if ((flags & ALIGN_H_CENTRE) != 0) paint.setTextAlign( Paint.Align.CENTER );
		else if ((flags & ALIGN_H_RIGHT) != 0) paint.setTextAlign( Paint.Align.RIGHT );
		else paint.setTextAlign( Paint.Align.LEFT );
		canvas.drawText(text, x, y, paint);
	}

	@UsedByJNI
	int blitterAlloc(int w, int h)
	{
		for(int i=0; i<blitters.length; i++) {
			if (blitters[i] == null) {
				float zoom = getXScale(zoomMatrix);
				blitters[i] = Bitmap.createBitmap(Math.round(zoom * w), Math.round(zoom * h), BITMAP_CONFIG);
				return i;
			}
		}
		throw new RuntimeException("No free blitter found!");
	}

	@UsedByJNI
	void blitterFree(int i)
	{
		if( blitters[i] == null ) return;
		blitters[i].recycle();
		blitters[i] = null;
	}

	@UsedByJNI
	void blitterSave(int i, int x, int y)
	{
		if( blitters[i] == null ) return;
		Canvas c = new Canvas(blitters[i]);
		Matrix m = new Matrix(inverseZoomMatrix);
		m.postTranslate(-x, -y);
		float zoom = getXScale(zoomMatrix);
		m.postScale(zoom, zoom);
		m.postTranslate(-overdrawX, -overdrawY);
		c.drawBitmap(bitmap, m, null);
	}

	@UsedByJNI
	void blitterLoad(int i, int x, int y)
	{
		if( blitters[i] == null ) return;
		Matrix m = new Matrix();
		float zoom = getXScale(zoomMatrix);
		m.postScale(1 / zoom, 1 / zoom);
		m.postTranslate(x, y);
		canvas.drawBitmap(blitters[i], m, null);
	}
}
