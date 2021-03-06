package lah.widgets;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import android.text.DynamicLayout;
import android.text.Editable;
import android.text.GetChars;
import android.text.InputType;
import android.text.Layout;
import android.text.Selection;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.SpannedString;
import android.text.TextPaint;
import android.text.TextWatcher;
import android.text.method.MetaKeyKeyListener;
import android.text.style.CharacterStyle;
import android.text.style.ParagraphStyle;
import android.text.style.UpdateAppearance;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewDebug;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.animation.AnimationUtils;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.PopupWindow;
import android.widget.RemoteViews.RemoteView;
import android.widget.Scroller;

/**
 * Simple text editing widget
 * 
 * TODO Fix pressing space automatically scroll to the current line
 * 
 * TODO {@link SpannableStringBuilder} is very inefficient in handling span! This is a main source of lagging when many
 * spans are bound to the text!
 * 
 * @author L.A.H.
 * 
 */
@RemoteView
@TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
public class TextArea extends View implements ViewTreeObserver.OnPreDrawListener, TextWatcher {

	@SuppressLint("HandlerLeak")
	public class Blink extends Handler implements Runnable {
		private boolean mCancelled;

		void cancel() {
			if (!mCancelled) {
				removeCallbacks(Blink.this);
				mCancelled = true;
			}
		}

		public void run() {
			if (mCancelled) {
				return;
			}

			removeCallbacks(Blink.this);

			if (shouldBlink()) {
				if (getLayout() != null) {
					invalidateCursorPath();
				}

				postAtTime(this, SystemClock.uptimeMillis() + BLINK);
			}
		}

		void uncancel() {
			mCancelled = false;
		}
	}

	/**
	 * A CursorController instance can be used to control a cursor in the text.
	 */
	public interface CursorController extends ViewTreeObserver.OnTouchModeChangeListener {
		public void hide();

		public void onDetached();

		public void show();
	}

	private class EditableInputConnection extends BaseInputConnection {

		// Keeps track of nested begin/end batch edit to ensure this connection always has a balanced impact on its
		// associated TextView. A negative value means that this connection has been finished by the InputMethodManager.
		private int mBatchEditNesting;

		public EditableInputConnection() {
			super(TextArea.this, true);
		}

		@Override
		public boolean beginBatchEdit() {
			synchronized (this) {
				if (mBatchEditNesting >= 0) {
					mBatchEditNesting++;
					return true;
				}
			}
			return false;
		}

		@Override
		public boolean endBatchEdit() {
			synchronized (this) {
				if (mBatchEditNesting > 0) {
					// When the connection is reset by the InputMethodManager and reportFinish
					// is called, some endBatchEdit calls may still be asynchronously received from the
					// IME. Do not take these into account, thus ensuring that this IC's final
					// contribution to mTextView's nested batch edit count is zero.
					TextArea.this.endBatchEdit();
					mBatchEditNesting--;
					return true;
				}
			}
			return false;
		}

		@Override
		public Editable getEditable() {
			return TextArea.this.mText;
		}
	}

	public abstract class HandleView extends View implements TextViewPositionListener {
		// Touch-up filter: number of previous positions remembered
		private static final int HISTORY_SIZE = 5;
		private static final int TOUCH_UP_FILTER_DELAY_AFTER = 150;
		private static final int TOUCH_UP_FILTER_DELAY_BEFORE = 350;
		private final PopupWindow mContainer;
		protected Drawable mDrawable;
		protected Drawable mDrawableLtr;
		protected Drawable mDrawableRtl;
		protected int mHotspotX;
		// Where the touch position should be on the handle to ensure a maximum cursor visibility
		private float mIdealVerticalOffset;
		private boolean mIsDragging;
		// Parent's (TextView) previous position in window
		private int mLastParentX, mLastParentY;
		private int mNumberPreviousOffsets = 0;
		// Previous text character offset
		private boolean mPositionHasChanged = true;
		// Position with respect to the parent TextView
		private int mPositionX, mPositionY;
		// Previous text character offset
		private int mPreviousOffset = -1;
		private int mPreviousOffsetIndex = 0;

		private final int[] mPreviousOffsets = new int[HISTORY_SIZE];
		private final long[] mPreviousOffsetsTimes = new long[HISTORY_SIZE];
		// Offsets the hotspot point up, so that cursor is not hidden by the finger when moving up
		private float mTouchOffsetY;
		// Offset from touch position to mPosition
		private float mTouchToWindowOffsetX, mTouchToWindowOffsetY;

		public HandleView(Drawable drawableLtr, Drawable drawableRtl) {
			super(TextArea.this.getContext());
			mContainer = new PopupWindow(getContext(), null, 0); // R.attr.textSelectHandleWindowStyle);
			mContainer.setSplitTouchEnabled(true);
			mContainer.setClippingEnabled(false);
			// mContainer.setWindowLayoutType(WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL);
			mContainer.setContentView(this);

			mDrawableLtr = drawableLtr;
			mDrawableRtl = drawableRtl;

			updateDrawable();

			final int handleHeight = mDrawable.getIntrinsicHeight();
			mTouchOffsetY = -0.3f * handleHeight;
			mIdealVerticalOffset = 0.7f * handleHeight;
		}

		private void addPositionToTouchUpFilter(int offset) {
			mPreviousOffsetIndex = (mPreviousOffsetIndex + 1) % HISTORY_SIZE;
			mPreviousOffsets[mPreviousOffsetIndex] = offset;
			mPreviousOffsetsTimes[mPreviousOffsetIndex] = SystemClock.uptimeMillis();
			mNumberPreviousOffsets++;
		}

		protected void dismiss() {
			mIsDragging = false;
			mContainer.dismiss();
			onDetached();
		}

		private void filterOnTouchUp() {
			final long now = SystemClock.uptimeMillis();
			int i = 0;
			int index = mPreviousOffsetIndex;
			final int iMax = Math.min(mNumberPreviousOffsets, HISTORY_SIZE);
			while (i < iMax && (now - mPreviousOffsetsTimes[index]) < TOUCH_UP_FILTER_DELAY_AFTER) {
				i++;
				index = (mPreviousOffsetIndex - i + HISTORY_SIZE) % HISTORY_SIZE;
			}

			if (i > 0 && i < iMax && (now - mPreviousOffsetsTimes[index]) > TOUCH_UP_FILTER_DELAY_BEFORE) {
				positionAtCursorOffset(mPreviousOffsets[index], false);
			}
		}

		public abstract int getCurrentCursorOffset();

		protected abstract int getHotspotX(Drawable drawable, boolean isRtlRun);

		public void hide() {
			dismiss();
			getPositionListener().removeSubscriber(this);
		}

		public boolean isDragging() {
			return mIsDragging;
		}

		public boolean isShowing() {
			return mContainer.isShowing();
		}

		private boolean isVisible() {
			// Always show a dragging handle.
			if (mIsDragging)
				return true;
			if (isInBatchEditMode())
				return false;
			return isPositionVisible(mPositionX + mHotspotX, mPositionY);
		}

		public boolean offsetHasBeenChanged() {
			return mNumberPreviousOffsets > 1;
		}

		public void onDetached() {
		}

		@Override
		protected void onDraw(Canvas c) {
			mDrawable.setBounds(0, 0, getRight() - getLeft(), getBottom() - getTop());
			mDrawable.draw(c);
		}

		void onHandleMoved() {
		}

		@Override
		protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
			setMeasuredDimension(mDrawable.getIntrinsicWidth(), mDrawable.getIntrinsicHeight());
		}

		@Override
		public boolean onTouchEvent(MotionEvent ev) {
			switch (ev.getActionMasked()) {
			case MotionEvent.ACTION_DOWN: {
				startTouchUpFilter(getCurrentCursorOffset());
				mTouchToWindowOffsetX = ev.getRawX() - mPositionX;
				mTouchToWindowOffsetY = ev.getRawY() - mPositionY;

				final PositionListener positionListener = getPositionListener();
				mLastParentX = positionListener.getPositionX();
				mLastParentY = positionListener.getPositionY();
				mIsDragging = true;
				break;
			}

			case MotionEvent.ACTION_MOVE: {
				final float rawX = ev.getRawX();
				final float rawY = ev.getRawY();

				// Vertical hysteresis: vertical down movement tends to snap to ideal offset
				final float previousVerticalOffset = mTouchToWindowOffsetY - mLastParentY;
				final float currentVerticalOffset = rawY - mPositionY - mLastParentY;
				float newVerticalOffset;
				if (previousVerticalOffset < mIdealVerticalOffset) {
					newVerticalOffset = Math.min(currentVerticalOffset, mIdealVerticalOffset);
					newVerticalOffset = Math.max(newVerticalOffset, previousVerticalOffset);
				} else {
					newVerticalOffset = Math.max(currentVerticalOffset, mIdealVerticalOffset);
					newVerticalOffset = Math.min(newVerticalOffset, previousVerticalOffset);
				}
				mTouchToWindowOffsetY = newVerticalOffset + mLastParentY;

				final float newPosX = rawX - mTouchToWindowOffsetX + mHotspotX;
				final float newPosY = rawY - mTouchToWindowOffsetY + mTouchOffsetY;

				updatePosition(newPosX, newPosY);
				break;
			}

			case MotionEvent.ACTION_UP:
				filterOnTouchUp();
				mIsDragging = false;
				break;

			case MotionEvent.ACTION_CANCEL:
				mIsDragging = false;
				break;
			}
			return true;
		}

		protected void positionAtCursorOffset(int offset, boolean parentScrolled) {
			// A HandleView relies on the layout, which may be nulled by external methods
			Layout layout = getLayout();
			if (layout == null) {
				// Will update controllers' state, hiding them and stopping selection mode if needed
				prepareCursorControllers();
				return;
			}

			boolean offsetChanged = offset != mPreviousOffset;
			if (offsetChanged || parentScrolled) {
				if (offsetChanged) {
					updateSelection(offset);
					addPositionToTouchUpFilter(offset);
				}
				final int line = layout.getLineForOffset(offset);

				mPositionX = (int) (layout.getPrimaryHorizontal(offset) - 0.5f - mHotspotX);
				mPositionY = layout.getLineBottom(line);

				// Take TextView's padding and scroll into account.
				mPositionX += viewportToContentHorizontalOffset();
				mPositionY += viewportToContentVerticalOffset();

				mPreviousOffset = offset;
				mPositionHasChanged = true;
			}
		}

		public void show() {
			if (isShowing())
				return;

			getPositionListener().addSubscriber(this, true /* local position may change */);

			// Make sure the offset is always considered new, even when focusing at same position
			mPreviousOffset = -1;
			positionAtCursorOffset(getCurrentCursorOffset(), false);
		}

		private void startTouchUpFilter(int offset) {
			mNumberPreviousOffsets = 0;
			addPositionToTouchUpFilter(offset);
		}

		protected void updateDrawable() {
			final int offset = getCurrentCursorOffset();
			final boolean isRtlCharAtOffset = getLayout().isRtlCharAt(offset);
			mDrawable = isRtlCharAtOffset ? mDrawableRtl : mDrawableLtr;
			mHotspotX = getHotspotX(mDrawable, isRtlCharAtOffset);
		}

		public abstract void updatePosition(float x, float y);

		public void updatePosition(int parentPositionX, int parentPositionY, boolean parentPositionChanged,
				boolean parentScrolled) {
			positionAtCursorOffset(getCurrentCursorOffset(), parentScrolled);
			if (parentPositionChanged || mPositionHasChanged) {
				if (mIsDragging) {
					// Update touchToWindow offset in case of parent scrolling while dragging
					if (parentPositionX != mLastParentX || parentPositionY != mLastParentY) {
						mTouchToWindowOffsetX += parentPositionX - mLastParentX;
						mTouchToWindowOffsetY += parentPositionY - mLastParentY;
						mLastParentX = parentPositionX;
						mLastParentY = parentPositionY;
					}

					onHandleMoved();
				}

				if (isVisible()) {
					final int positionX = parentPositionX + mPositionX;
					final int positionY = parentPositionY + mPositionY;
					if (isShowing()) {
						mContainer.update(positionX, positionY, -1, -1);
					} else {
						mContainer.showAtLocation(this, Gravity.NO_GRAVITY, positionX, positionY);
					}
				} else {
					if (isShowing()) {
						dismiss();
					}
				}

				mPositionHasChanged = false;
			}
		}

		protected abstract void updateSelection(int offset);
	}

	public static class InputMethodState {
		int mBatchEditNesting;
		int mChangedStart, mChangedEnd, mChangedDelta;
		boolean mContentChanged;
		boolean mCursorChanged;
		Rect mCursorRectInWindow = new Rect();
		float[] mTmpOffset = new float[2];
		RectF mTmpRectF = new RectF();
	}

	public class InsertionHandleView extends HandleView {
		private static final int DELAY_BEFORE_HANDLE_FADES_OUT = 4000;
		private static final int RECENT_CUT_COPY_DURATION = 15 * 1000; // seconds

		// Used to detect taps on the insertion handle, which will affect the ActionPopupWindow
		private float mDownPositionX, mDownPositionY;
		private Runnable mHider;

		public InsertionHandleView(Drawable drawable) {
			super(drawable, drawable);
		}

		@Override
		public int getCurrentCursorOffset() {
			return getSelectionStart();
		}

		@Override
		protected int getHotspotX(Drawable drawable, boolean isRtlRun) {
			return drawable.getIntrinsicWidth() / 2;
		}

		private void hideAfterDelay() {
			if (mHider == null) {
				mHider = new Runnable() {
					public void run() {
						hide();
					}
				};
			} else {
				removeHiderCallback();
			}
			postDelayed(mHider, DELAY_BEFORE_HANDLE_FADES_OUT);
		}

		@Override
		public void onDetached() {
			super.onDetached();
			removeHiderCallback();
		}

		@Override
		void onHandleMoved() {
			super.onHandleMoved();
			removeHiderCallback();
		}

		@Override
		public boolean onTouchEvent(MotionEvent ev) {
			final boolean result = super.onTouchEvent(ev);

			switch (ev.getActionMasked()) {
			case MotionEvent.ACTION_DOWN:
				mDownPositionX = ev.getRawX();
				mDownPositionY = ev.getRawY();
				break;

			case MotionEvent.ACTION_UP:
				if (!offsetHasBeenChanged()) {
					final float deltaX = mDownPositionX - ev.getRawX();
					final float deltaY = mDownPositionY - ev.getRawY();
					final float distanceSquared = deltaX * deltaX + deltaY * deltaY;

					final ViewConfiguration viewConfiguration = ViewConfiguration.get(getContext());
					final int touchSlop = viewConfiguration.getScaledTouchSlop();

					if (distanceSquared < touchSlop * touchSlop) {
					}
				}
				hideAfterDelay();
				break;

			case MotionEvent.ACTION_CANCEL:
				hideAfterDelay();
				break;

			default:
				break;
			}

			return result;
		}

		private void removeHiderCallback() {
			if (mHider != null) {
				removeCallbacks(mHider);
			}
		}

		@Override
		public void show() {
			super.show();

			final long durationSinceCutOrCopy = SystemClock.uptimeMillis() - TextArea.LAST_CUT_OR_COPY_TIME;
			if (durationSinceCutOrCopy < RECENT_CUT_COPY_DURATION) {
			}

			hideAfterDelay();
		}

		@Override
		public void updatePosition(float x, float y) {
			positionAtCursorOffset(getOffsetForPosition(x, y), false);
		}

		@Override
		public void updateSelection(int offset) {
			Selection.setSelection(getText(), offset);
		}
	}

	public class InsertionPointCursorController implements CursorController {
		private InsertionHandleView mHandle;

		private InsertionHandleView getHandle() {
			if (mSelectHandleCenter == null) {
				mSelectHandleCenter = getResources().getDrawable(mTextSelectHandleRes);
			}
			if (mHandle == null) {
				mHandle = new InsertionHandleView(mSelectHandleCenter);
			}
			return mHandle;
		}

		public void hide() {
			if (mHandle != null) {
				mHandle.hide();
			}
		}

		@Override
		public void onDetached() {
			final ViewTreeObserver observer = getViewTreeObserver();
			observer.removeOnTouchModeChangeListener(this);

			if (mHandle != null)
				mHandle.onDetached();
		}

		public void onTouchModeChanged(boolean isInTouchMode) {
			if (!isInTouchMode) {
				hide();
			}
		}

		public void show() {
			getHandle().show();
		}
	}

	public class PositionListener implements ViewTreeObserver.OnPreDrawListener {
		// 3 handles
		private final int MAXIMUM_NUMBER_OF_LISTENERS = 6;
		private boolean mCanMove[] = new boolean[MAXIMUM_NUMBER_OF_LISTENERS];
		private int mNumberOfListeners;
		private boolean mPositionHasChanged = true;
		private TextViewPositionListener[] mPositionListeners = new TextViewPositionListener[MAXIMUM_NUMBER_OF_LISTENERS];
		// Absolute position of the TextView with respect to its parent window
		private int mPositionX, mPositionY;
		private boolean mScrollHasChanged;
		final int[] mTempCoords = new int[2];

		public void addSubscriber(TextViewPositionListener positionListener, boolean canMove) {
			if (mNumberOfListeners == 0) {
				updatePosition();
				ViewTreeObserver vto = getViewTreeObserver();
				vto.addOnPreDrawListener(this);
			}

			int emptySlotIndex = -1;
			for (int i = 0; i < MAXIMUM_NUMBER_OF_LISTENERS; i++) {
				TextViewPositionListener listener = mPositionListeners[i];
				if (listener == positionListener) {
					return;
				} else if (emptySlotIndex < 0 && listener == null) {
					emptySlotIndex = i;
				}
			}

			mPositionListeners[emptySlotIndex] = positionListener;
			mCanMove[emptySlotIndex] = canMove;
			mNumberOfListeners++;
		}

		public int getPositionX() {
			return mPositionX;
		}

		public int getPositionY() {
			return mPositionY;
		}

		@Override
		public boolean onPreDraw() {
			updatePosition();

			for (int i = 0; i < MAXIMUM_NUMBER_OF_LISTENERS; i++) {
				if (mPositionHasChanged || mScrollHasChanged || mCanMove[i]) {
					TextViewPositionListener positionListener = mPositionListeners[i];
					if (positionListener != null) {
						positionListener.updatePosition(mPositionX, mPositionY, mPositionHasChanged, mScrollHasChanged);
					}
				}
			}

			mScrollHasChanged = false;
			return true;
		}

		public void onScrollChanged() {
			mScrollHasChanged = true;
		}

		public void removeSubscriber(TextViewPositionListener positionListener) {
			for (int i = 0; i < MAXIMUM_NUMBER_OF_LISTENERS; i++) {
				if (mPositionListeners[i] == positionListener) {
					mPositionListeners[i] = null;
					mNumberOfListeners--;
					break;
				}
			}

			if (mNumberOfListeners == 0) {
				ViewTreeObserver vto = getViewTreeObserver();
				vto.removeOnPreDrawListener(this);
			}
		}

		private void updatePosition() {
			getLocationInWindow(mTempCoords);
			mPositionHasChanged = mTempCoords[0] != mPositionX || mTempCoords[1] != mPositionY;
			mPositionX = mTempCoords[0];
			mPositionY = mTempCoords[1];
		}
	}

	public static class TextUtils {

		private static Object sLock = new Object();

		private static char[] sTemp = null;

		public static void getChars(CharSequence s, int start, int end, char[] dest, int destoff) {
			Class<? extends CharSequence> c = s.getClass();

			if (c == String.class)
				((String) s).getChars(start, end, dest, destoff);
			else if (c == StringBuffer.class)
				((StringBuffer) s).getChars(start, end, dest, destoff);
			else if (c == StringBuilder.class)
				((StringBuilder) s).getChars(start, end, dest, destoff);
			else if (s instanceof GetChars)
				((GetChars) s).getChars(start, end, dest, destoff);
			else {
				for (int i = start; i < end; i++)
					dest[destoff++] = s.charAt(i);
			}
		}

		public static int idealByteArraySize(int need) {
			for (int i = 4; i < 32; i++)
				if (need <= (1 << i) - 12)
					return (1 << i) - 12;

			return need;
		}

		public static int idealCharArraySize(int need) {
			return idealByteArraySize(need * 2) / 2;
		}

		/**
		 * Returns true if the string is null or 0-length.
		 * 
		 * @param str
		 *            the string to be examined
		 * @return true if str is null or zero length
		 */
		public static boolean isEmpty(CharSequence str) {
			if (str == null || str.length() == 0)
				return true;
			else
				return false;
		}

		static char[] obtain(int len) {
			char[] buf;

			synchronized (sLock) {
				buf = sTemp;
				sTemp = null;
			}

			if (buf == null || buf.length < len)
				buf = new char[idealCharArraySize(len)];

			return buf;
		}

		/**
		 * Pack 2 int values into a long, useful as a return value for a range
		 * 
		 * @see #unpackRangeStartFromLong(long)
		 * @see #unpackRangeEndFromLong(long)
		 * @hide
		 */
		public static long packRangeInLong(int start, int end) {
			return (((long) start) << 32) | end;
		}

		static void recycle(char[] temp) {
			if (temp.length > 1000)
				return;

			synchronized (sLock) {
				sTemp = temp;
			}
		}

		public static CharSequence stringOrSpannedString(CharSequence source) {
			if (source == null)
				return null;
			if (source instanceof SpannedString)
				return source;
			if (source instanceof Spanned)
				return new SpannedString(source);

			return source.toString();
		}

		/**
		 * Create a new String object containing the given range of characters from the source string. This is different
		 * than simply calling {@link CharSequence#subSequence(int, int) CharSequence.subSequence} in that it does not
		 * preserve any style runs in the source sequence, allowing a more efficient implementation.
		 */
		public static String substring(CharSequence source, int start, int end) {
			if (source instanceof String)
				return ((String) source).substring(start, end);
			if (source instanceof StringBuilder)
				return ((StringBuilder) source).substring(start, end);
			if (source instanceof StringBuffer)
				return ((StringBuffer) source).substring(start, end);

			char[] temp = obtain(end - start);
			getChars(source, start, end, temp, 0);
			String ret = new String(temp, 0, end - start);
			recycle(temp);

			return ret;
		}

		/**
		 * Get the end value from a range packed in a long by {@link #packRangeInLong(int, int)}
		 * 
		 * @see #unpackRangeStartFromLong(long)
		 * @see #packRangeInLong(int, int)
		 * @hide
		 */
		public static int unpackRangeEndFromLong(long range) {
			return (int) (range & 0x00000000FFFFFFFFL);
		}

		/**
		 * Get the start value from a range packed in a long by {@link #packRangeInLong(int, int)}
		 * 
		 * @see #unpackRangeEndFromLong(long)
		 * @see #packRangeInLong(int, int)
		 * @hide
		 */
		public static int unpackRangeStartFromLong(long range) {
			return (int) (range >>> 32);
		}

		private TextUtils() { /* cannot be instantiated */
		}
	}

	public interface TextViewPositionListener {

		public void updatePosition(int parentPositionX, int parentPositionY, boolean parentPositionChanged,
				boolean parentScrolled);
	}

	private static final int ANIMATED_SCROLL_GAP = 250;

	static final int BLINK = 500;

	// private static final int CHANGE_WATCHER_PRIORITY = 100;

	// static final boolean DEBUG_EXTRACT = false;

	// static final int EXTRACT_NOTHING = -2;

	static final int EXTRACT_UNKNOWN = -1;

	static long LAST_CUT_OR_COPY_TIME;

	private static final int LINES = 1, EMS = LINES, PIXELS = 2;

	static final String LOG_TAG = "TextView";

	private static final float[] TEMP_POSITION = new float[2];

	private static final RectF TEMP_RECTF = new RectF();

	// XXX should be much larger
	private static final int VERY_WIDE = 1024 * 1024;

	/*
	 * Kick-start the font cache for the zygote process (to pay the cost of initializing freetype for our default font
	 * only once).
	 */
	static {
		Paint p = new Paint();
		p.setAntiAlias(true);
		// We don't care about the result, just the side-effect of measuring.
		p.measureText("H");
	}

	private static int desired(Layout layout) {
		int n = layout.getLineCount();
		CharSequence text = layout.getText();
		float max = 0;
		// if any line was wrapped, we can't use it. but it's ok for the last line not to have a newline
		for (int i = 0; i < n - 1; i++) {
			if (text.charAt(layout.getLineEnd(i) - 1) != '\n')
				return -1;
		}
		for (int i = 0; i < n; i++) {
			max = Math.max(max, layout.getLineWidth(i));
		}
		return (int) Math.ceil(max);
	}

	/**
	 * Fast round from float to int. This is faster than Math.round() thought it may return slightly different results.
	 * It does not try to handle (in any meaningful way) NaN or infinities.
	 */
	public static int round(float value) {
		long lx = (long) (value * (65536 * 256f));
		return (int) ((lx + 0x800000) >> 24);
	}

	private Blink mBlink;

	ClipboardManager mClipboard;

	int mCursorCount; // Current number of used mCursorDrawable: 0 (resource=0), 1 or 2 (split)

	private final Drawable[] mCursorDrawable = new Drawable[2];

	int mCursorDrawableRes;

	boolean mCursorVisible = true;

	private int mCurTextColor;

	private int mDeferScroll = -1;

	private int mDesiredHeightAtMeasure = -1;

	boolean mDiscardNextActionUp;

	private boolean mDispatchTemporaryDetach;

	private int mGravity = Gravity.TOP | Gravity.START;

	int mHighlightColor = 0x6633B5E5;

	private final Paint mHighlightPaint;

	private Path mHighlightPath;

	private boolean mHighlightPathBogus = true;

	private boolean mHorizontallyScrolling;

	boolean mIgnoreActionUpEvent;

	private final InputMethodManager mIMM;

	private final InputMethodState mIMS;

	boolean mInBatchEditControllers;

	private boolean mIncludePad = true;

	private boolean mInsertionControllerEnabled;

	InsertionPointCursorController mInsertionPointCursorController;

	float mLastDownPositionX, mLastDownPositionY;

	int mLastLayoutHeight;

	private long mLastScroll;

	private DynamicLayout mLayout;

	private ColorStateList mLinkTextColor;

	private int mMaximum = Integer.MAX_VALUE;

	private int mMaxMode = LINES;

	private int mMaxWidth = Integer.MAX_VALUE;

	private int mMaxWidthMode = PIXELS;

	private int mMinimum = 0;

	private int mMinMode = LINES;

	private int mMinWidth = 0;

	private int mMinWidthMode = PIXELS;

	private int mOldMaximum = mMaximum;

	private int mOldMaxMode = mMaxMode;

	// Global listener that detects changes in the global position of the TextView
	private PositionListener mPositionListener = new PositionListener();

	private boolean mPreDrawRegistered;

	boolean mPreserveDetachedSelection;

	private Scroller mScroller;

	private Drawable mSelectHandleCenter;

	boolean mSelectionMoved;

	private float mShadowRadius, mShadowDx, mShadowDy;

	private long mShowCursor;

	private boolean mTemporaryDetach;

	// tmp primitives, so we don't alloc them on each draw
	private Rect mTempRect;

	@ViewDebug.ExportedProperty(category = "text")
	private Editable mText = new SpannableStringBuilder();

	private ColorStateList mTextColor;

	boolean mTextIsSelectable;

	private final TextPaint mTextPaint;

	// int mTextSelectHandleLeftRes = R.drawable.text_select_handle_left;
	// int mTextSelectHandleRightRes = R.drawable.text_select_handle_right;
	int mTextSelectHandleRes = R.drawable.text_select_handle_middle;

	boolean mTouchFocusSelected;

	public TextArea(Context context, AttributeSet attrs) {
		super(context, attrs);
		mIMM = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
		mClipboard = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
		mIMS = new InputMethodState();
		mTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
		mTextPaint.density = getResources().getDisplayMetrics().density;
		mHighlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
		setTextColor(0xFF000000);
		setRawTextSize(20);
		setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
		setFocusable(true);
		setClickable(true);
		setLongClickable(true);
		prepareCursorControllers();
		// TODO L.A.H. Force testing code, should change accordingly
		mCursorCount = 1;
		mCursorDrawable[0] = getContext().getResources().getDrawable(R.drawable.text_select_handle_middle);
	}

	/**
	 * Convenience method: Append the specified text to the TextView's display buffer, upgrading it to
	 * BufferType.EDITABLE if it was not already editable.
	 */
	public final void append(CharSequence text) {
		append(text, 0, text.length());
	}

	/**
	 * Convenience method: Append the specified text slice to the TextView's display buffer, upgrading it to
	 * BufferType.EDITABLE if it was not already editable.
	 */
	public void append(CharSequence text, int start, int end) {
		mText.append(text, start, end);
	}

	/**
	 * Make a new Layout based on the already-measured size of the view, on the assumption that it was measured
	 * correctly at some point.
	 */
	private void assumeLayout() {
		int width = getRight() - getLeft() - getCompoundPaddingLeft() - getCompoundPaddingRight();

		if (width < 1) {
			width = 0;
		}

		if (mHorizontallyScrolling) {
			width = VERY_WIDE;
		}

		makeNewLayout(width, false);
	}

	public void beginBatchEdit() {
		mInBatchEditControllers = true;
		int nesting = ++mIMS.mBatchEditNesting;
		if (nesting == 1) {
			mIMS.mCursorChanged = false;
			mIMS.mChangedDelta = 0;
			if (mIMS.mContentChanged) {
				// We already have a pending change from somewhere else, so turn this into a full update.
				mIMS.mChangedStart = 0;
				mIMS.mChangedEnd = getText().length();
			} else {
				mIMS.mChangedStart = EXTRACT_UNKNOWN;
				mIMS.mChangedEnd = EXTRACT_UNKNOWN;
				mIMS.mContentChanged = false;
			}
			onBeginBatchEdit();
		}
	}

	/**
	 * Move the point, specified by the offset, into the view if it is needed. This has to be called after layout.
	 * Returns true if anything changed.
	 */
	public boolean bringPointIntoView(int offset) {
		if (isLayoutRequested()) {
			mDeferScroll = offset;
			return false;
		}
		boolean changed = false;

		Layout layout = mLayout;

		if (layout == null)
			return changed;

		int line = layout.getLineForOffset(offset);

		// FIXME: Is it okay to truncate this, or should we round?
		final int x = (int) layout.getPrimaryHorizontal(offset);
		final int top = layout.getLineTop(line);
		final int bottom = layout.getLineTop(line + 1);

		int left = (int) Math.floor(layout.getLineLeft(line));
		int right = (int) Math.ceil(layout.getLineRight(line));
		int ht = layout.getHeight();

		int hspace = getRight() - getLeft() - getCompoundPaddingLeft() - getCompoundPaddingRight();
		int vspace = getBottom() - getTop() - getExtendedPaddingTop() - getExtendedPaddingBottom();

		int hslack = (bottom - top) / 2;
		int vslack = hslack;

		if (vslack > vspace / 4)
			vslack = vspace / 4;
		if (hslack > hspace / 4)
			hslack = hspace / 4;

		int hs = getScrollX();
		int vs = getScrollY();

		if (top - vs < vslack)
			vs = top - vslack;
		if (bottom - vs > vspace - vslack)
			vs = bottom - (vspace - vslack);
		if (ht - vs < vspace)
			vs = ht - vspace;
		if (0 - vs > 0)
			vs = 0;

		if (x - hs < hslack) {
			hs = x - hslack;
		}
		if (x - hs > hspace - hslack) {
			hs = x - (hspace - hslack);
		}

		if (right - hs < hspace)
			hs = right - hspace;
		if (left - hs > 0)
			hs = left;

		if (hs != getScrollX() || vs != getScrollY()) {
			if (mScroller == null) {
				scrollTo(hs, vs);
			} else {
				long duration = AnimationUtils.currentAnimationTimeMillis() - mLastScroll;
				int dx = hs - getScrollX();
				int dy = vs - getScrollY();

				if (duration > ANIMATED_SCROLL_GAP) {
					mScroller.startScroll(getScrollX(), getScrollY(), dx, dy);
					awakenScrollBars(mScroller.getDuration());
					invalidate();
				} else {
					if (!mScroller.isFinished()) {
						mScroller.abortAnimation();
					}

					scrollBy(dx, dy);
				}

				mLastScroll = AnimationUtils.currentAnimationTimeMillis();
			}

			changed = true;
		}

		if (isFocused()) {
			// This offsets because getInterestingRect() is in terms of viewport coordinates, but
			// requestRectangleOnScreen() is in terms of content coordinates.

			// The offsets here are to ensure the rectangle we are using is
			// within our view bounds, in case the cursor is on the far left
			// or right. If it isn't withing the bounds, then this request
			// will be ignored.
			if (mTempRect == null)
				mTempRect = new Rect();
			mTempRect.set(x - 2, top, x + 2, bottom);
			getInterestingRect(mTempRect, line);
			mTempRect.offset(getScrollX(), getScrollY());

			if (requestRectangleOnScreen(mTempRect)) {
				changed = true;
			}
		}

		return changed;
	}

	/**
	 * Returns true if anything changed.
	 */
	private boolean bringTextIntoView() {
		Layout layout = mLayout;
		int line = 0;
		if ((mGravity & Gravity.VERTICAL_GRAVITY_MASK) == Gravity.BOTTOM) {
			line = layout.getLineCount() - 1;
		}

		int vspace = getBottom() - getTop() - getExtendedPaddingTop() - getExtendedPaddingBottom();
		int ht = layout.getHeight();
		int scrollx = (int) Math.floor(layout.getLineLeft(line)), scrolly = 0;
		if (ht < vspace) {
			scrolly = 0;
		} else {
			if ((mGravity & Gravity.VERTICAL_GRAVITY_MASK) == Gravity.BOTTOM) {
				scrolly = ht - vspace;
			} else {
				scrolly = 0;
			}
		}

		if (scrollx != getScrollX() || scrolly != getScrollY()) {
			scrollTo(scrollx, scrolly);
			return true;
		} else {
			return false;
		}
	}

	@Override
	public void cancelLongPress() {
		super.cancelLongPress();
		mIgnoreActionUpEvent = true;
	}

	/**
	 * Check whether entirely new text requires a new view layout or merely a new text layout.
	 */
	private void checkForRelayout() {
		// If we have a fixed width, we can just swap in a new text layout
		// if the text height stays the same or if the view height is fixed.

		if ((getLayoutParams().width != LayoutParams.WRAP_CONTENT || (mMaxWidthMode == mMinWidthMode && mMaxWidth == mMinWidth))
				&& (getRight() - getLeft() - getCompoundPaddingLeft() - getCompoundPaddingRight() > 0)) {
			// Static width, so try making a new text layout.

			// int oldht = mLayout.getHeight();
			int want = mLayout.getWidth();

			/*
			 * No need to bring the text into view, since the size is not changing (unless we do the requestLayout(), in
			 * which case it will happen at measure).
			 */
			makeNewLayout(want, false);

			// We lose: the height has changed and we have a dynamic height.
			// Request a new view layout using our new text layout.
			requestLayout();
			invalidate();
		} else {
			// Dynamic width, so we have no choice but to request a new
			// view layout with a new text layout.
			nullLayouts();
			requestLayout();
			invalidate();
		}
	}

	/**
	 * Check whether a change to the existing text layout requires a new view layout.
	 */
	private void checkForResize() {
		boolean sizeChanged = false;

		if (mLayout != null) {
			// Check if our width changed
			if (getLayoutParams().width == LayoutParams.WRAP_CONTENT) {
				sizeChanged = true;
				invalidate();
			}

			// Check if our height changed
			if (getLayoutParams().height == LayoutParams.WRAP_CONTENT) {
				int desiredHeight = getDesiredHeight();

				if (desiredHeight != this.getHeight()) {
					sizeChanged = true;
				}
			} else if (getLayoutParams().height == LayoutParams.MATCH_PARENT) {
				if (mDesiredHeightAtMeasure >= 0) {
					int desiredHeight = getDesiredHeight();

					if (desiredHeight != mDesiredHeightAtMeasure) {
						sizeChanged = true;
					}
				}
			}
		}

		if (sizeChanged) {
			requestLayout();
			// caller will have already invalidated
		}
	}

	/**
	 * Use {@link BaseInputConnection#removeComposingSpans BaseInputConnection.removeComposingSpans()} to remove any IME
	 * composing state from this text view.
	 */
	public void clearComposingText() {
		BaseInputConnection.removeComposingSpans(mText);
	}

	@Override
	public void computeScroll() {
		if (mScroller != null) {
			if (mScroller.computeScrollOffset()) {
				// getScrollX() = mScroller.getCurrX();
				// getScrollY() = mScroller.getCurrY();
				// invalidateParentCaches();
				postInvalidate(); // So we draw again
			}
		}
	}

	private void convertFromViewportToContentCoordinates(Rect r) {
		final int horizontalOffset = viewportToContentHorizontalOffset();
		r.left += horizontalOffset;
		r.right += horizontalOffset;

		final int verticalOffset = viewportToContentVerticalOffset();
		r.top += verticalOffset;
		r.bottom += verticalOffset;
	}

	float convertToLocalHorizontalCoordinate(float x) {
		x -= getTotalPaddingLeft();
		// Clamp the position to inside of the view.
		x = Math.max(0.0f, x);
		x = Math.min(getWidth() - getTotalPaddingRight() - 1, x);
		x += getScrollX();
		return x;
	}

	/**
	 * Returns true, only while processing a touch gesture, if the initial touch down event caused focus to move to the
	 * text view and as a result its selection changed. Only valid while processing the touch gesture of interest, in an
	 * editable text view.
	 */
	public boolean didTouchFocusSelect() {
		return mTouchFocusSelected;
	}

	private void drawCursor(Canvas canvas, int cursorOffsetVertical) {
		final boolean translate = cursorOffsetVertical != 0;
		if (translate)
			canvas.translate(0, cursorOffsetVertical);
		for (int i = 0; i < mCursorCount; i++) {
			mCursorDrawable[i].draw(canvas);
		}
		if (translate)
			canvas.translate(0, -cursorOffsetVertical);
	}

	public void endBatchEdit() {
		mInBatchEditControllers = false;
		int nesting = --mIMS.mBatchEditNesting;
		if (nesting == 0) {
			finishBatchEdit(mIMS);
		}
	}

	void ensureEndedBatchEdit() {
		if (mIMS.mBatchEditNesting != 0) {
			mIMS.mBatchEditNesting = 0;
			finishBatchEdit(mIMS);
		}
	}

	void finishBatchEdit(final InputMethodState ims) {
		onEndBatchEdit();
		if (ims.mContentChanged /* || ims.mSelectionModeChanged */) {
			updateAfterEdit();
			// reportExtractedText();
		} else if (ims.mCursorChanged) {
			// Cheezy way to get us to report the current cursor location.
			invalidateCursor();
		}
	}

	@Override
	public int getBaseline() {
		if (mLayout == null) {
			return super.getBaseline();
		}

		int voffset = 0;
		if ((mGravity & Gravity.VERTICAL_GRAVITY_MASK) != Gravity.TOP) {
			voffset = getVerticalOffset(true);
		}

		return getExtendedPaddingTop() + voffset + mLayout.getLineBaseline(0);
	}

	@Override
	protected int getBottomPaddingOffset() {
		return (int) Math.max(0, mShadowDy + mShadowRadius);
	}

	private int getBottomVerticalOffset(boolean forceNormal) {
		int voffset = 0;
		final int gravity = mGravity & Gravity.VERTICAL_GRAVITY_MASK;

		Layout l = mLayout;

		if (gravity != Gravity.BOTTOM) {
			int boxht;

			if (l == null) {
				boxht = getMeasuredHeight() - getCompoundPaddingTop() - getCompoundPaddingBottom();
			} else {
				boxht = getMeasuredHeight() - getExtendedPaddingTop() - getExtendedPaddingBottom();
			}
			int textht = l.getHeight();

			if (textht < boxht) {
				if (gravity == Gravity.TOP)
					voffset = boxht - textht;
				else
					// (gravity == Gravity.CENTER_VERTICAL)
					voffset = (boxht - textht) >> 1;
			}
		}
		return voffset;
	}

	/**
	 * Returns the bottom padding of the view, plus space for the bottom Drawable if any.
	 */
	public int getCompoundPaddingBottom() {
		return getPaddingBottom();
	}

	/**
	 * Returns the end padding of the view, plus space for the end Drawable if any.
	 */
	public int getCompoundPaddingEnd() {
		switch (getLayoutDirection()) {
		default:
		case LAYOUT_DIRECTION_LTR:
			return getCompoundPaddingRight();
		case LAYOUT_DIRECTION_RTL:
			return getCompoundPaddingLeft();
		}
	}

	/**
	 * Returns the left padding of the view, plus space for the left Drawable if any.
	 */
	public int getCompoundPaddingLeft() {
		return getPaddingLeft();
	}

	/**
	 * Returns the right padding of the view, plus space for the right Drawable if any.
	 */
	public int getCompoundPaddingRight() {
		return getPaddingRight();
	}

	/**
	 * Returns the start padding of the view, plus space for the start Drawable if any.
	 */
	public int getCompoundPaddingStart() {
		switch (getLayoutDirection()) {
		default:
		case LAYOUT_DIRECTION_LTR:
			return getCompoundPaddingLeft();
		case LAYOUT_DIRECTION_RTL:
			return getCompoundPaddingRight();
		}
	}

	/**
	 * Returns the top padding of the view, plus space for the top Drawable if any.
	 */
	public int getCompoundPaddingTop() {
		return getPaddingTop();
	}

	/**
	 * <p>
	 * Return the current color selected for normal text.
	 * </p>
	 * 
	 * @return Returns the current text color.
	 */
	public final int getCurrentTextColor() {
		return mCurTextColor;
	}

	private int getDesiredHeight() {
		return Math.max(getDesiredHeight(mLayout, true), getDesiredHeight(null, false));
	}

	private int getDesiredHeight(Layout layout, boolean cap) {
		if (layout == null) {
			return 0;
		}

		int linecount = layout.getLineCount();
		int pad = getCompoundPaddingTop() + getCompoundPaddingBottom();
		int desired = layout.getLineTop(linecount);

		desired += pad;

		if (mMaxMode == LINES) {
			/*
			 * Don't cap the hint to a certain number of lines. (Do cap it, though, if we have a maximum pixel height.)
			 */
			if (cap) {
				if (linecount > mMaximum) {
					desired = layout.getLineTop(mMaximum);
					desired += pad;
					linecount = mMaximum;
				}
			}
		} else {
			desired = Math.min(desired, mMaximum);
		}

		if (mMinMode == LINES) {
			if (linecount < mMinimum) {
				desired += getLineHeight() * (mMinimum - linecount);
			}
		} else {
			desired = Math.max(desired, mMinimum);
		}

		// Check against our minimum height
		desired = Math.max(desired, getSuggestedMinimumHeight());

		return desired;
	}

	/**
	 * Returns the extended bottom padding of the view, including both the bottom Drawable if any and any extra space to
	 * keep more than maxLines of text from showing. It is only valid to call this after measuring.
	 */
	public int getExtendedPaddingBottom() {
		if (mMaxMode != LINES) {
			return getCompoundPaddingBottom();
		}

		if (mLayout.getLineCount() <= mMaximum) {
			return getCompoundPaddingBottom();
		}

		int top = getCompoundPaddingTop();
		int bottom = getCompoundPaddingBottom();
		int viewht = getHeight() - top - bottom;
		int layoutht = mLayout.getLineTop(mMaximum);

		if (layoutht >= viewht) {
			return bottom;
		}

		final int gravity = mGravity & Gravity.VERTICAL_GRAVITY_MASK;
		if (gravity == Gravity.TOP) {
			return bottom + viewht - layoutht;
		} else if (gravity == Gravity.BOTTOM) {
			return bottom;
		} else { // (gravity == Gravity.CENTER_VERTICAL)
			return bottom + (viewht - layoutht) / 2;
		}
	}

	/**
	 * Returns the extended top padding of the view, including both the top Drawable if any and any extra space to keep
	 * more than maxLines of text from showing. It is only valid to call this after measuring.
	 */
	public int getExtendedPaddingTop() {
		if (mMaxMode != LINES) {
			return getCompoundPaddingTop();
		}

		if (mLayout.getLineCount() <= mMaximum) {
			return getCompoundPaddingTop();
		}

		int top = getCompoundPaddingTop();
		int bottom = getCompoundPaddingBottom();
		int viewht = getHeight() - top - bottom;
		int layoutht = mLayout.getLineTop(mMaximum);

		if (layoutht >= viewht) {
			return top;
		}

		final int gravity = mGravity & Gravity.VERTICAL_GRAVITY_MASK;
		if (gravity == Gravity.TOP) {
			return top;
		} else if (gravity == Gravity.BOTTOM) {
			return top + viewht - layoutht;
		} else { // (gravity == Gravity.CENTER_VERTICAL)
			return top + (viewht - layoutht) / 2;
		}
	}

	@Override
	public void getFocusedRect(Rect r) {
		if (mLayout == null) {
			super.getFocusedRect(r);
			return;
		}

		int selEnd = getSelectionEnd();
		if (selEnd < 0) {
			super.getFocusedRect(r);
			return;
		}

		int selStart = getSelectionStart();
		if (selStart < 0 || selStart >= selEnd) {
			int line = mLayout.getLineForOffset(selEnd);
			r.top = mLayout.getLineTop(line);
			r.bottom = mLayout.getLineBottom(line);
			r.left = (int) mLayout.getPrimaryHorizontal(selEnd) - 2;
			r.right = r.left + 4;
		} else {
			int lineStart = mLayout.getLineForOffset(selStart);
			int lineEnd = mLayout.getLineForOffset(selEnd);
			r.top = mLayout.getLineTop(lineStart);
			r.bottom = mLayout.getLineBottom(lineEnd);
			if (lineStart == lineEnd) {
				r.left = (int) mLayout.getPrimaryHorizontal(selStart);
				r.right = (int) mLayout.getPrimaryHorizontal(selEnd);
			} else {
				// Selection extends across multiple lines -- make the focused
				// rect cover the entire width.
				if (mHighlightPathBogus) {
					if (mHighlightPath == null)
						mHighlightPath = new Path();
					mHighlightPath.reset();
					mLayout.getSelectionPath(selStart, selEnd, mHighlightPath);
					mHighlightPathBogus = false;
				}
				synchronized (TEMP_RECTF) {
					mHighlightPath.computeBounds(TEMP_RECTF, true);
					r.left = (int) TEMP_RECTF.left - 1;
					r.right = (int) TEMP_RECTF.right + 1;
				}
			}
		}

		// Adjust for padding and gravity.
		int paddingLeft = getCompoundPaddingLeft();
		int paddingTop = getExtendedPaddingTop();
		if ((mGravity & Gravity.VERTICAL_GRAVITY_MASK) != Gravity.TOP) {
			paddingTop += getVerticalOffset(false);
		}
		r.offset(paddingLeft, paddingTop);
		int paddingBottom = getExtendedPaddingBottom();
		r.bottom += paddingBottom;
	}

	/**
	 * Returns the horizontal and vertical alignment of this TextView.
	 * 
	 * @see android.view.Gravity
	 * @attr ref android.R.styleable#TextView_gravity
	 */
	public int getGravity() {
		return mGravity;
	}

	/**
	 * @return the color used to display the selection highlight
	 * 
	 * @see #setHighlightColor(int)
	 * 
	 * @attr ref android.R.styleable#TextView_textColorHighlight
	 */
	public int getHighlightColor() {
		return mHighlightColor;
	}

	/**
	 * Returns whether the text is allowed to be wider than the View is. If false, the text will be wrapped to the width
	 * of the View.
	 * 
	 * @attr ref android.R.styleable#TextView_scrollHorizontally
	 * @hide
	 */
	public boolean getHorizontallyScrolling() {
		return mHorizontallyScrolling;
	}

	/**
	 * @hide
	 */
	public int getHorizontalOffsetForDrawables() {
		return 0;
	}

	/**
	 * Gets whether the TextView includes extra top and bottom padding to make room for accents that go above the normal
	 * ascent and descent.
	 * 
	 * @see #setIncludeFontPadding(boolean)
	 * 
	 * @attr ref android.R.styleable#TextView_includeFontPadding
	 */
	public boolean getIncludeFontPadding() {
		return mIncludePad;
	}

	private InsertionPointCursorController getInsertionController() {
		if (!mInsertionControllerEnabled) {
			return null;
		}

		if (mInsertionPointCursorController == null) {
			mInsertionPointCursorController = new InsertionPointCursorController();

			final ViewTreeObserver observer = getViewTreeObserver();
			observer.addOnTouchModeChangeListener(mInsertionPointCursorController);
		}

		return mInsertionPointCursorController;
	}

	private void getInterestingRect(Rect r, int line) {
		convertFromViewportToContentCoordinates(r);

		// Rectangle can can be expanded on first and last line to take
		// padding into account.
		// TODO Take left/right padding into account too?
		if (line == 0)
			r.top -= getExtendedPaddingTop();
		if (line == mLayout.getLineCount() - 1)
			r.bottom += getExtendedPaddingBottom();
	}

	/**
	 * @return the Layout that is currently being used to display the text. This can be null if the text or width has
	 *         recently changes.
	 */
	public final Layout getLayout() {
		return mLayout;
	}

	@Override
	protected int getLeftPaddingOffset() {
		return getCompoundPaddingLeft() - getPaddingLeft() + (int) Math.min(0, mShadowDx - mShadowRadius);
	}

	int getLineAtCoordinate(float y) {
		y -= getTotalPaddingTop();
		// Clamp the position to inside of the view.
		y = Math.max(0.0f, y);
		y = Math.min(getHeight() - getTotalPaddingBottom() - 1, y);
		y += getScrollY();
		return getLayout().getLineForVertical((int) y);
	}

	/**
	 * Return the baseline for the specified line (0...getLineCount() - 1) If bounds is not null, return the top, left,
	 * right, bottom extents of the specified line in it. If the internal Layout has not been built, return 0 and set
	 * bounds to (0, 0, 0, 0)
	 * 
	 * @param line
	 *            which line to examine (0..getLineCount() - 1)
	 * @param bounds
	 *            Optional. If not null, it returns the extent of the line
	 * @return the Y-coordinate of the baseline
	 */
	public int getLineBounds(int line, Rect bounds) {
		if (mLayout == null) {
			if (bounds != null) {
				bounds.set(0, 0, 0, 0);
			}
			return 0;
		} else {
			int baseline = mLayout.getLineBounds(line, bounds);

			int voffset = getExtendedPaddingTop();
			if ((mGravity & Gravity.VERTICAL_GRAVITY_MASK) != Gravity.TOP) {
				voffset += getVerticalOffset(true);
			}
			if (bounds != null) {
				bounds.offset(getCompoundPaddingLeft(), voffset);
			}
			return baseline + voffset;
		}
	}

	/**
	 * @return the height of one standard line in pixels. Note that markup within the text can cause individual lines to
	 *         be taller or shorter than this height, and the layout may contain additional first- or last-line padding.
	 */
	public int getLineHeight() {
		return round(mTextPaint.getFontMetricsInt(null));
	}

	/**
	 * @return the list of colors used to paint the links in the text, for the different states of this TextView
	 * 
	 * @see #setLinkTextColor(ColorStateList)
	 * @see #setLinkTextColor(int)
	 * 
	 * @attr ref android.R.styleable#TextView_textColorLink
	 */
	public final ColorStateList getLinkTextColors() {
		return mLinkTextColor;
	}

	/**
	 * @return the maximum width of the TextView, expressed in ems or -1 if the maximum width was set in pixels instead
	 *         (using {@link #setMaxWidth(int)} or {@link #setWidth(int)}).
	 * 
	 * @see #setMaxEms(int)
	 * @see #setEms(int)
	 * 
	 * @attr ref android.R.styleable#TextView_maxEms
	 */
	public int getMaxEms() {
		return mMaxWidthMode == EMS ? mMaxWidth : -1;
	}

	/**
	 * @return the maximum height of this TextView expressed in pixels, or -1 if the maximum height was set in number of
	 *         lines instead using {@link #setMaxLines(int) or #setLines(int)}.
	 * 
	 * @see #setMaxHeight(int)
	 * 
	 * @attr ref android.R.styleable#TextView_maxHeight
	 */
	public int getMaxHeight() {
		return mMaxMode == PIXELS ? mMaximum : -1;
	}

	/**
	 * @return the maximum number of lines displayed in this TextView, or -1 if the maximum height was set in pixels
	 *         instead using {@link #setMaxHeight(int) or #setHeight(int)}.
	 * 
	 * @see #setMaxLines(int)
	 * 
	 * @attr ref android.R.styleable#TextView_maxLines
	 */
	public int getMaxLines() {
		return mMaxMode == LINES ? mMaximum : -1;
	}

	/**
	 * @return the maximum width of the TextView, in pixels or -1 if the maximum width was set in ems instead (using
	 *         {@link #setMaxEms(int)} or {@link #setEms(int)}).
	 * 
	 * @see #setMaxWidth(int)
	 * @see #setWidth(int)
	 * 
	 * @attr ref android.R.styleable#TextView_maxWidth
	 */
	public int getMaxWidth() {
		return mMaxWidthMode == PIXELS ? mMaxWidth : -1;
	}

	/**
	 * @return the minimum width of the TextView, expressed in ems or -1 if the minimum width was set in pixels instead
	 *         (using {@link #setMinWidth(int)} or {@link #setWidth(int)}).
	 * 
	 * @see #setMinEms(int)
	 * @see #setEms(int)
	 * 
	 * @attr ref android.R.styleable#TextView_minEms
	 */
	public int getMinEms() {
		return mMinWidthMode == EMS ? mMinWidth : -1;
	}

	/**
	 * @return the minimum height of this TextView expressed in pixels, or -1 if the minimum height was set in number of
	 *         lines instead using {@link #setMinLines(int) or #setLines(int)}.
	 * 
	 * @see #setMinHeight(int)
	 * 
	 * @attr ref android.R.styleable#TextView_minHeight
	 */
	public int getMinHeight() {
		return mMinMode == PIXELS ? mMinimum : -1;
	}

	/**
	 * @return the minimum number of lines displayed in this TextView, or -1 if the minimum height was set in pixels
	 *         instead using {@link #setMinHeight(int) or #setHeight(int)}.
	 * 
	 * @see #setMinLines(int)
	 * 
	 * @attr ref android.R.styleable#TextView_minLines
	 */
	public int getMinLines() {
		return mMinMode == LINES ? mMinimum : -1;
	}

	/**
	 * @return the minimum width of the TextView, in pixels or -1 if the minimum width was set in ems instead (using
	 *         {@link #setMinEms(int)} or {@link #setEms(int)}).
	 * 
	 * @see #setMinWidth(int)
	 * @see #setWidth(int)
	 * 
	 * @attr ref android.R.styleable#TextView_minWidth
	 */
	public int getMinWidth() {
		return mMinWidthMode == PIXELS ? mMinWidth : -1;
	}

	private int getOffsetAtCoordinate(int line, float x) {
		x = convertToLocalHorizontalCoordinate(x);
		return getLayout().getOffsetForHorizontal(line, x);
	}

	/**
	 * Get the character offset closest to the specified absolute position. A typical use case is to pass the result of
	 * {@link MotionEvent#getX()} and {@link MotionEvent#getY()} to this method.
	 * 
	 * @param x
	 *            The horizontal absolute position of a point on screen
	 * @param y
	 *            The vertical absolute position of a point on screen
	 * @return the character offset for the character whose position is closest to the specified position. Returns -1 if
	 *         there is no layout.
	 */
	public int getOffsetForPosition(float x, float y) {
		if (getLayout() == null)
			return -1;
		final int line = getLineAtCoordinate(y);
		final int offset = getOffsetAtCoordinate(line, x);
		return offset;
	}

	/**
	 * @return the base paint used for the text. Please use this only to consult the Paint's properties and not to
	 *         change them.
	 */
	public TextPaint getPaint() {
		return mTextPaint;
	}

	/**
	 * @return the flags on the Paint being used to display the text.
	 * @see Paint#getFlags
	 */
	public int getPaintFlags() {
		return mTextPaint.getFlags();
	}

	private PositionListener getPositionListener() {
		return mPositionListener;
	}

	@Override
	protected int getRightPaddingOffset() {
		return -(getCompoundPaddingRight() - getPaddingRight()) + (int) Math.max(0, mShadowDx + mShadowRadius);
	}

	/**
	 * Convenience for {@link Selection#getSelectionEnd}.
	 */
	@ViewDebug.ExportedProperty(category = "text")
	public int getSelectionEnd() {
		return Selection.getSelectionEnd(getText());
	}

	/**
	 * Convenience for {@link Selection#getSelectionStart}.
	 */
	@ViewDebug.ExportedProperty(category = "text")
	public int getSelectionStart() {
		return Selection.getSelectionStart(getText());
	}

	/**
	 * @return the horizontal offset of the shadow layer
	 * 
	 * @see #setShadowLayer(float, float, float, int)
	 * 
	 * @attr ref android.R.styleable#TextView_shadowDx
	 */
	public float getShadowDx() {
		return mShadowDx;
	}

	/**
	 * @return the vertical offset of the shadow layer
	 * 
	 * @see #setShadowLayer(float, float, float, int)
	 * 
	 * @attr ref android.R.styleable#TextView_shadowDy
	 */
	public float getShadowDy() {
		return mShadowDy;
	}

	/**
	 * Gets the radius of the shadow layer.
	 * 
	 * @return the radius of the shadow layer. If 0, the shadow layer is not visible
	 * 
	 * @see #setShadowLayer(float, float, float, int)
	 * 
	 * @attr ref android.R.styleable#TextView_shadowRadius
	 */
	public float getShadowRadius() {
		return mShadowRadius;
	}

	@ViewDebug.CapturedViewProperty
	public Editable getText() {
		return mText;
	}

	/**
	 * Gets the text colors for the different states (normal, selected, focused) of the TextView.
	 * 
	 * @see #setTextColor(ColorStateList)
	 * @see #setTextColor(int)
	 */
	public final ColorStateList getTextColors() {
		return mTextColor;
	}

	/**
	 * @return the extent by which text is currently being stretched horizontally. This will usually be 1.
	 */
	public float getTextScaleX() {
		return mTextPaint.getTextScaleX();
	}

	/**
	 * @return the size (in pixels) of the default text size in this TextView.
	 */
	@ViewDebug.ExportedProperty(category = "text")
	public float getTextSize() {
		return mTextPaint.getTextSize();
	}

	@Override
	protected int getTopPaddingOffset() {
		return (int) Math.min(0, mShadowDy - mShadowRadius);
	}

	/**
	 * Returns the total bottom padding of the view, including the bottom Drawable if any, the extra space to keep more
	 * than maxLines from showing, and the vertical offset for gravity, if any.
	 */
	public int getTotalPaddingBottom() {
		return getExtendedPaddingBottom() + getBottomVerticalOffset(true);
	}

	/**
	 * Returns the total end padding of the view, including the end Drawable if any.
	 */
	public int getTotalPaddingEnd() {
		return getCompoundPaddingEnd();
	}

	/**
	 * Returns the total left padding of the view, including the left Drawable if any.
	 */
	public int getTotalPaddingLeft() {
		return getCompoundPaddingLeft();
	}

	/**
	 * Returns the total right padding of the view, including the right Drawable if any.
	 */
	public int getTotalPaddingRight() {
		return getCompoundPaddingRight();
	}

	/**
	 * Returns the total start padding of the view, including the start Drawable if any.
	 */
	public int getTotalPaddingStart() {
		return getCompoundPaddingStart();
	}

	/**
	 * Returns the total top padding of the view, including the top Drawable if any, the extra space to keep more than
	 * maxLines from showing, and the vertical offset for gravity, if any.
	 */
	public int getTotalPaddingTop() {
		return getExtendedPaddingTop() + getVerticalOffset(true);
	}

	CharSequence getTransformedText(int start, int end) {
		return mText.subSequence(start, end);
	}

	public Typeface getTypeface() {
		return mTextPaint.getTypeface();
	}

	private Path getUpdatedHighlightPath() {
		Path highlight = null;
		Paint highlightPaint = mHighlightPaint;
		final int selStart = getSelectionStart();
		final int selEnd = getSelectionEnd();
		if ((isFocused() || isPressed()) && selStart >= 0) {
			if (selStart == selEnd) {
				if (isCursorVisible() && (SystemClock.uptimeMillis() - mShowCursor) % (2 * BLINK) < BLINK) {
					if (mHighlightPathBogus) {
						if (mHighlightPath == null)
							mHighlightPath = new Path();
						mHighlightPath.reset();
						mLayout.getCursorPath(selStart, mHighlightPath, mText);
						updateCursorsPositions();
						mHighlightPathBogus = false;
					}

					// XXX should pass to skin instead of drawing directly
					highlightPaint.setColor(mCurTextColor);
					highlightPaint.setStyle(Paint.Style.STROKE);
					highlight = mHighlightPath;
				}
			} else {
				if (mHighlightPathBogus) {
					if (mHighlightPath == null)
						mHighlightPath = new Path();
					mHighlightPath.reset();
					mLayout.getSelectionPath(selStart, selEnd, mHighlightPath);
					mHighlightPathBogus = false;
				}

				// XXX should pass to skin instead of drawing directly
				highlightPaint.setColor(mHighlightColor);
				highlightPaint.setStyle(Paint.Style.FILL);

				highlight = mHighlightPath;
			}
		}
		return highlight;
	}

	int getVerticalOffset(boolean forceNormal) {
		int voffset = 0;
		final int gravity = mGravity & Gravity.VERTICAL_GRAVITY_MASK;

		Layout l = mLayout;

		if (gravity != Gravity.TOP) {
			int boxht;

			if (l == null) {
				boxht = getMeasuredHeight() - getCompoundPaddingTop() - getCompoundPaddingBottom();
			} else {
				boxht = getMeasuredHeight() - getExtendedPaddingTop() - getExtendedPaddingBottom();
			}
			int textht = l.getHeight();

			if (textht < boxht) {
				if (gravity == Gravity.BOTTOM)
					voffset = boxht - textht;
				else
					// (gravity == Gravity.CENTER_VERTICAL)
					voffset = (boxht - textht) >> 1;
			}
		}
		return voffset;
	}

	/**
	 * @return True if this view supports insertion handles.
	 */
	boolean hasInsertionController() {
		return mInsertionControllerEnabled;
	}

	/**
	 * Return true iff there is a selection inside this text view.
	 */
	public boolean hasSelection() {
		final int selectionStart = getSelectionStart();
		final int selectionEnd = getSelectionEnd();

		return selectionStart >= 0 && selectionStart != selectionEnd;
	}

	/**
	 * Hides the insertion controller and stops text selection mode, hiding the selection controller
	 */
	void hideControllers() {
		hideCursorControllers();
	}

	private void hideCursorControllers() {
		hideInsertionPointCursorController();
	}

	private void hideInsertionPointCursorController() {
		if (mInsertionPointCursorController != null) {
			mInsertionPointCursorController.hide();
		}
	}

	void invalidateCursor() {
		int where = getSelectionEnd();
		invalidateCursor(where, where, where);
	}

	private void invalidateCursor(int a, int b, int c) {
		if (a >= 0 || b >= 0 || c >= 0) {
			int start = Math.min(Math.min(a, b), c);
			int end = Math.max(Math.max(a, b), c);
			invalidateRegion(start, end, true /* Also invalidates blinking cursor */);
		}
	}

	void invalidateCursorPath() {
		if (mHighlightPathBogus) {
			invalidateCursor();
		} else {
			final int horizontalPadding = getCompoundPaddingLeft();
			final int verticalPadding = getExtendedPaddingTop() + getVerticalOffset(true);

			if (mCursorCount == 0) {
				synchronized (TEMP_RECTF) {
					/*
					 * The reason for this concern about the thickness of the cursor and doing the floor/ceil on the
					 * coordinates is that some EditTexts (notably textfields in the Browser) have anti-aliased text
					 * where not all the characters are necessarily at integer-multiple locations. This should make sure
					 * the entire cursor gets invalidated instead of sometimes missing half a pixel.
					 */
					float thick = (float) Math.ceil(mTextPaint.getStrokeWidth());
					if (thick < 1.0f) {
						thick = 1.0f;
					}

					thick /= 2.0f;

					// mHighlightPath is guaranteed to be non null at that point.
					mHighlightPath.computeBounds(TEMP_RECTF, false);

					invalidate((int) Math.floor(horizontalPadding + TEMP_RECTF.left - thick),
							(int) Math.floor(verticalPadding + TEMP_RECTF.top - thick),
							(int) Math.ceil(horizontalPadding + TEMP_RECTF.right + thick),
							(int) Math.ceil(verticalPadding + TEMP_RECTF.bottom + thick));
				}
			} else {
				for (int i = 0; i < mCursorCount; i++) {
					Rect bounds = mCursorDrawable[i].getBounds();
					invalidate(bounds.left + horizontalPadding, bounds.top + verticalPadding, bounds.right
							+ horizontalPadding, bounds.bottom + verticalPadding);
				}
			}
		}
	}

	/**
	 * Invalidates the region of text enclosed between the start and end text offsets.
	 */
	void invalidateRegion(int start, int end, boolean invalidateCursor) {
		if (mLayout == null) {
			invalidate();
		} else {
			int lineStart = mLayout.getLineForOffset(start);
			int top = mLayout.getLineTop(lineStart);

			// This is ridiculous, but the descent from the line above
			// can hang down into the line we really want to redraw,
			// so we have to invalidate part of the line above to make
			// sure everything that needs to be redrawn really is.
			// (But not the whole line above, because that would cause
			// the same problem with the descenders on the line above it!)
			if (lineStart > 0) {
				top -= mLayout.getLineDescent(lineStart - 1);
			}

			int lineEnd;

			if (start == end)
				lineEnd = lineStart;
			else
				lineEnd = mLayout.getLineForOffset(end);

			int bottom = mLayout.getLineBottom(lineEnd);

			// mEditor can be null in case selection is set programmatically.
			if (invalidateCursor) {
				for (int i = 0; i < mCursorCount; i++) {
					Rect bounds = mCursorDrawable[i].getBounds();
					top = Math.min(top, bounds.top);
					bottom = Math.max(bottom, bounds.bottom);
				}
			}

			final int compoundPaddingLeft = getCompoundPaddingLeft();
			final int verticalPadding = getExtendedPaddingTop() + getVerticalOffset(true);

			int left, right;
			if (lineStart == lineEnd && !invalidateCursor) {
				left = (int) mLayout.getPrimaryHorizontal(start);
				right = (int) (mLayout.getPrimaryHorizontal(end) + 1.0);
				left += compoundPaddingLeft;
				right += compoundPaddingLeft;
			} else {
				// Rectangle bounding box when the region spans several lines
				left = compoundPaddingLeft;
				right = getWidth() - getCompoundPaddingRight();
			}

			invalidate(getScrollX() + left, verticalPadding + top, getScrollX() + right, verticalPadding + bottom);
		}
	}

	/**
	 * @return whether or not the cursor is visible (assuming this TextView is editable)
	 * 
	 * @see #setCursorVisible(boolean)
	 * 
	 * @attr ref android.R.styleable#TextView_cursorVisible
	 */
	public boolean isCursorVisible() {
		// true is the default value
		return mCursorVisible;
	}

	boolean isInBatchEditMode() {
		return mIMS.mBatchEditNesting > 0;
	}

	/**
	 * Returns whether this text view is a current input method target. The default implementation just checks with
	 * {@link InputMethodManager}.
	 */
	public boolean isInputMethodTarget() {
		return mIMM.isActive(this);
	}

	@Override
	protected boolean isPaddingOffsetRequired() {
		return mShadowRadius != 0;
	}

	private boolean isPositionVisible(int positionX, int positionY) {
		synchronized (TEMP_POSITION) {
			final float[] position = TEMP_POSITION;
			position[0] = positionX;
			position[1] = positionY;
			View view = this;

			while (view != null) {
				if (view != this) {
					// Local scroll is already taken into account in positionX/Y
					position[0] -= view.getScrollX();
					position[1] -= view.getScrollY();
				}

				if (position[0] < 0 || position[1] < 0 || position[0] > view.getWidth()
						|| position[1] > view.getHeight()) {
					return false;
				}

				if (!view.getMatrix().isIdentity()) {
					view.getMatrix().mapPoints(position);
				}

				position[0] += view.getLeft();
				position[1] += view.getTop();

				final ViewParent parent = view.getParent();
				if (parent instanceof View) {
					view = (View) parent;
				} else {
					// We've reached the ViewRoot, stop iterating
					view = null;
				}
			}
		}

		// We've been able to walk up the view hierarchy and the position was never clipped
		return true;
	}

	public boolean isTextSelectable() {
		return mTextIsSelectable;
	}

	/**
	 * Returns the length, in characters, of the text managed by this TextView
	 */
	public int length() {
		return mText.length();
	}

	void makeBlink() {
		if (shouldBlink()) {
			mShowCursor = SystemClock.uptimeMillis();
			if (mBlink == null)
				mBlink = new Blink();
			mBlink.removeCallbacks(mBlink);
			mBlink.postAtTime(mBlink, mShowCursor + BLINK);
		} else {
			if (mBlink != null)
				mBlink.removeCallbacks(mBlink);
		}
	}

	/**
	 * The width passed in is now the desired layout width, not the full view width with padding.
	 */
	protected void makeNewLayout(int wantWidth, boolean bringIntoView) {
		// Update "old" cached values
		mOldMaximum = mMaximum;
		mOldMaxMode = mMaxMode;

		mHighlightPathBogus = true;

		if (wantWidth < 0) {
			wantWidth = 0;
		}

		mLayout = new DynamicLayout(mText, mTextPaint, wantWidth, Layout.Alignment.ALIGN_NORMAL, 1.0f, 0.0f,
				mIncludePad);

		if (bringIntoView) {
			registerForPreDraw();
		}

		// CursorControllers need a non-null mLayout
		prepareCursorControllers();
	}

	/**
	 * Move the cursor, if needed, so that it is at an offset that is visible to the user. This will not move the cursor
	 * if it represents more than one character (a selection range). This will only work if the TextView contains
	 * spannable text; otherwise it will do nothing.
	 * 
	 * @return True if the cursor was actually moved, false otherwise.
	 */
	public boolean moveCursorToVisibleOffset() {
		int start = getSelectionStart();
		int end = getSelectionEnd();
		if (start != end) {
			return false;
		}

		// First: make sure the line is visible on screen:

		int line = mLayout.getLineForOffset(start);

		final int top = mLayout.getLineTop(line);
		final int bottom = mLayout.getLineTop(line + 1);
		final int vspace = getBottom() - getTop() - getExtendedPaddingTop() - getExtendedPaddingBottom();
		int vslack = (bottom - top) / 2;
		if (vslack > vspace / 4)
			vslack = vspace / 4;
		final int vs = getScrollY();

		if (top < (vs + vslack)) {
			line = mLayout.getLineForVertical(vs + vslack + (bottom - top));
		} else if (bottom > (vspace + vs - vslack)) {
			line = mLayout.getLineForVertical(vspace + vs - vslack - (bottom - top));
		}

		// Next: make sure the character is visible on screen:

		final int hspace = getRight() - getLeft() - getCompoundPaddingLeft() - getCompoundPaddingRight();
		final int hs = getScrollX();
		final int leftChar = mLayout.getOffsetForHorizontal(line, hs);
		final int rightChar = mLayout.getOffsetForHorizontal(line, hspace + hs);

		// line might contain bidirectional text
		final int lowChar = leftChar < rightChar ? leftChar : rightChar;
		final int highChar = leftChar > rightChar ? leftChar : rightChar;

		int newStart = start;
		if (newStart < lowChar) {
			newStart = lowChar;
		} else if (newStart > highChar) {
			newStart = highChar;
		}

		if (newStart != start) {
			Selection.setSelection(mText, newStart);
			return true;
		}

		return false;
	}

	private void nullLayouts() {
		mLayout = null;
		prepareCursorControllers();
	}

	@Override
	protected void onAttachedToWindow() {
		super.onAttachedToWindow();
		mTemporaryDetach = false;
		// mEditor.onAttachedToWindow();
		mTemporaryDetach = false;
		final ViewTreeObserver observer = getViewTreeObserver();
		// No need to create the controller.
		// The get method will add the listener on controller creation.
		if (mInsertionPointCursorController != null) {
			observer.addOnTouchModeChangeListener(mInsertionPointCursorController);
		}
		if (hasTransientState() && getSelectionStart() != getSelectionEnd()) {
			// Since transient state is reference counted make sure it stays matched
			// with our own calls to it for managing selection.
			// The action mode callback will set this back again when/if the action mode starts.
			setHasTransientState(false);
		}
	}

	/**
	 * Called by the framework in response to a request to begin a batch of edit operations through a call to link
	 * {@link #beginBatchEdit()}.
	 */
	public void onBeginBatchEdit() {
		// intentionally empty
	}

	@Override
	public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
		outAttrs.inputType = InputType.TYPE_NULL;
		outAttrs.imeOptions |= EditorInfo.IME_FLAG_NO_ENTER_ACTION;
		outAttrs.initialSelStart = getSelectionStart();
		outAttrs.initialSelEnd = getSelectionEnd();
		return new EditableInputConnection();
	}

	@Override
	protected void onDetachedFromWindow() {
		super.onDetachedFromWindow();

		if (mPreDrawRegistered) {
			getViewTreeObserver().removeOnPreDrawListener(this);
			mPreDrawRegistered = false;
		}

		// mEditor.onDetachedFromWindow();
		if (mBlink != null) {
			mBlink.removeCallbacks(mBlink);
		}

		if (mInsertionPointCursorController != null) {
			mInsertionPointCursorController.onDetached();
		}
		mPreserveDetachedSelection = true;
		hideControllers();
		mPreserveDetachedSelection = false;
		mTemporaryDetach = false;
	}

	@Override
	public boolean onDragEvent(DragEvent event) {
		switch (event.getAction()) {
		case DragEvent.ACTION_DRAG_STARTED:
			return hasInsertionController();

		case DragEvent.ACTION_DRAG_ENTERED:
			TextArea.this.requestFocus();
			return true;

		case DragEvent.ACTION_DRAG_LOCATION:
			final int offset = getOffsetForPosition(event.getX(), event.getY());
			Selection.setSelection(mText, offset);
			return true;

		case DragEvent.ACTION_DROP:
			// onDrop(event);
			return true;

		case DragEvent.ACTION_DRAG_ENDED:
		case DragEvent.ACTION_DRAG_EXITED:
		default:
			return true;
		}
	}

	@SuppressWarnings("deprecation")
	@Override
	protected void onDraw(Canvas canvas) {
		// Draw the background for this view
		super.onDraw(canvas);

		final int compoundPaddingLeft = getCompoundPaddingLeft();
		final int compoundPaddingTop = getCompoundPaddingTop();
		final int compoundPaddingRight = getCompoundPaddingRight();
		final int compoundPaddingBottom = getCompoundPaddingBottom();
		final int scrollX = getScrollX();
		final int scrollY = getScrollY();
		final int right = getRight();
		final int left = getLeft();
		final int bottom = getBottom();
		final int top = getTop();

		int color = mCurTextColor;

		if (mLayout == null) {
			assumeLayout();
		}

		Layout layout = mLayout;

		mTextPaint.setColor(color);
		mTextPaint.drawableState = getDrawableState();

		canvas.save();

		int extendedPaddingTop = getExtendedPaddingTop();
		int extendedPaddingBottom = getExtendedPaddingBottom();

		final int vspace = getBottom() - getTop() - compoundPaddingBottom - compoundPaddingTop;
		final int maxScrollY = mLayout.getHeight() - vspace;

		float clipLeft = compoundPaddingLeft + scrollX;
		float clipTop = (scrollY == 0) ? 0 : extendedPaddingTop + scrollY;
		float clipRight = right - left - compoundPaddingRight + scrollX;
		float clipBottom = bottom - top + scrollY - ((scrollY == maxScrollY) ? 0 : extendedPaddingBottom);

		if (mShadowRadius != 0) {
			clipLeft += Math.min(0, mShadowDx - mShadowRadius);
			clipRight += Math.max(0, mShadowDx + mShadowRadius);

			clipTop += Math.min(0, mShadowDy - mShadowRadius);
			clipBottom += Math.max(0, mShadowDy + mShadowRadius);
		}

		canvas.clipRect(clipLeft, clipTop, clipRight, clipBottom);

		int voffsetText = 0;
		int voffsetCursor = 0;

		// translate in by our padding
		/* shortcircuit calling getVerticaOffset() */
		if ((mGravity & Gravity.VERTICAL_GRAVITY_MASK) != Gravity.TOP) {
			voffsetText = getVerticalOffset(false);
			voffsetCursor = getVerticalOffset(true);
		}
		canvas.translate(compoundPaddingLeft, extendedPaddingTop + voffsetText);

		final int cursorOffsetVertical = voffsetCursor - voffsetText;

		// TODO L.A.H. remove the following merge draw methods
		// the following is inlined from mEditor.onDraw(canvas, layout, highlight, mHighlightPaint,
		// cursorOffsetVertical);
		Path highlight = getUpdatedHighlightPath();
		final int selectionStart = getSelectionStart();
		final int selectionEnd = getSelectionEnd();
		if (mIMS.mBatchEditNesting == 0) {
			if (mIMM.isActive(this)) {
				boolean reported = false;
				// if (mIMS.mContentChanged || mIMS.mSelectionModeChanged) {
				// // We are in extract mode and the content has changed
				// // in some way... just report complete new text to the
				// // input method.
				// // reported = reportExtractedText();
				// }
				if (!reported && highlight != null) {
					int candStart = -1;
					int candEnd = -1;
					Spannable sp = getText();
					candStart = EditableInputConnection.getComposingSpanStart(sp);
					candEnd = EditableInputConnection.getComposingSpanEnd(sp);
					mIMM.updateSelection(this, selectionStart, selectionEnd, candStart, candEnd);
				}
			}

			if (mIMM.isWatchingCursor(this) && highlight != null) {
				highlight.computeBounds(mIMS.mTmpRectF, true);
				mIMS.mTmpOffset[0] = mIMS.mTmpOffset[1] = 0;

				canvas.getMatrix().mapPoints(mIMS.mTmpOffset);
				mIMS.mTmpRectF.offset(mIMS.mTmpOffset[0], mIMS.mTmpOffset[1]);

				mIMS.mTmpRectF.offset(0, cursorOffsetVertical);

				mIMS.mCursorRectInWindow.set((int) (mIMS.mTmpRectF.left + 0.5), (int) (mIMS.mTmpRectF.top + 0.5),
						(int) (mIMS.mTmpRectF.right + 0.5), (int) (mIMS.mTmpRectF.bottom + 0.5));

				mIMM.updateCursor(this, mIMS.mCursorRectInWindow.left, mIMS.mCursorRectInWindow.top,
						mIMS.mCursorRectInWindow.right, mIMS.mCursorRectInWindow.bottom);
			}
		}

		if (highlight != null && selectionStart == selectionEnd && mCursorCount > 0) {
			drawCursor(canvas, cursorOffsetVertical);
			// Rely on the drawable entirely, do not draw the cursor line.
			// Has to be done after the IMM related code above which relies on the highlight.
			highlight = null;
		}
		// TODO L.A.H. Unfortunately, hardware acceleration is not publicly accessible
		layout.draw(canvas, highlight, mHighlightPaint, cursorOffsetVertical);

		canvas.restore();
	}

	/**
	 * Called by the framework in response to a request to end a batch of edit operations through a call to link
	 * {@link #endBatchEdit}.
	 */
	public void onEndBatchEdit() {
		// intentionally empty
	}

	@Override
	public void onFinishTemporaryDetach() {
		super.onFinishTemporaryDetach();
		// Only track when onStartTemporaryDetach() is called directly,
		// usually because this instance is an editable field in a list
		if (!mDispatchTemporaryDetach)
			mTemporaryDetach = false;
		mTemporaryDetach = false;
	}

	@Override
	protected void onFocusChanged(boolean focused, int direction, Rect previouslyFocusedRect) {
		if (mTemporaryDetach) {
			// If we are temporarily in the detach state, then do nothing.
			super.onFocusChanged(focused, direction, previouslyFocusedRect);
			return;
		}
		// Copied from Editor.onFocusChanged
		mShowCursor = SystemClock.uptimeMillis();
		ensureEndedBatchEdit();
		if (focused) {
			mTouchFocusSelected = true;
			mSelectionMoved = false;
			makeBlink();
		} else {
			// Don't leave us in the middle of a batch edit.
			onEndBatchEdit();
			if (mTemporaryDetach)
				mPreserveDetachedSelection = true;
			hideControllers();
			if (mTemporaryDetach)
				mPreserveDetachedSelection = false;
		} // End of Editor.onFocusChanged
		if (focused) {
			MetaKeyKeyListener.resetMetaState(mText);
		}
		super.onFocusChanged(focused, direction, previouslyFocusedRect);
	}

	@Override
	protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
		super.onLayout(changed, left, top, right, bottom);
		if (mDeferScroll >= 0) {
			int curs = mDeferScroll;
			mDeferScroll = -1;
			bringPointIntoView(Math.min(curs, mText.length()));
		}
	}

	@Override
	protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
		int widthMode = MeasureSpec.getMode(widthMeasureSpec);
		int heightMode = MeasureSpec.getMode(heightMeasureSpec);
		int widthSize = MeasureSpec.getSize(widthMeasureSpec);
		int heightSize = MeasureSpec.getSize(heightMeasureSpec);

		int width;
		int height;

		int des = -1;
		boolean fromexisting = false;

		if (widthMode == MeasureSpec.EXACTLY) {
			// Parent has told us how big to be. So be it.
			width = widthSize;
		} else {
			if (mLayout != null) {
				des = desired(mLayout);
			}

			if (des < 0) {
				// boring = BoringLayout.isBoring(mText, mTextPaint, mBoring);
				// if (boring != null) {
				// mBoring = boring;
				// }
			} else {
				fromexisting = true;
			}

			// if (boring == null || boring == UNKNOWN_BORING) {
			if (des < 0) {
				des = (int) Math.ceil(Layout.getDesiredWidth(mText, mTextPaint));
			}
			width = des;
			// } else {
			// width = boring.width;
			// }

			width += getCompoundPaddingLeft() + getCompoundPaddingRight();

			if (mMaxWidthMode == EMS) {
				width = Math.min(width, mMaxWidth * getLineHeight());
			} else {
				width = Math.min(width, mMaxWidth);
			}

			if (mMinWidthMode == EMS) {
				width = Math.max(width, mMinWidth * getLineHeight());
			} else {
				width = Math.max(width, mMinWidth);
			}

			// Check against our minimum width
			width = Math.max(width, getSuggestedMinimumWidth());

			if (widthMode == MeasureSpec.AT_MOST) {
				width = Math.min(widthSize, width);
			}
		}

		int want = width - getCompoundPaddingLeft() - getCompoundPaddingRight();
		int unpaddedWidth = want;

		if (mHorizontallyScrolling)
			want = VERY_WIDE;

		if (mLayout == null) {
			makeNewLayout(want, false);
		} else {
			final boolean layoutChanged = (mLayout.getWidth() != want);

			final boolean widthChanged = (want > mLayout.getWidth()) && (fromexisting && des >= 0 && des <= want);

			final boolean maximumChanged = (mMaxMode != mOldMaxMode) || (mMaximum != mOldMaximum);

			if (layoutChanged || maximumChanged) {
				if (!maximumChanged && widthChanged) {
					mLayout.increaseWidthTo(want);
				} else {
					makeNewLayout(want, false);
				}
			} else {
				// Nothing has changed
			}
		}

		if (heightMode == MeasureSpec.EXACTLY) {
			// Parent has told us how big to be. So be it.
			height = heightSize;
			mDesiredHeightAtMeasure = -1;
		} else {
			int desired = getDesiredHeight();
			height = desired;
			mDesiredHeightAtMeasure = desired;
			if (heightMode == MeasureSpec.AT_MOST) {
				height = Math.min(desired, heightSize);
			}
		}

		int unpaddedHeight = height - getCompoundPaddingTop() - getCompoundPaddingBottom();
		if (mMaxMode == LINES && mLayout.getLineCount() > mMaximum) {
			unpaddedHeight = Math.min(unpaddedHeight, mLayout.getLineTop(mMaximum));
		}

		/*
		 * We didn't let makeNewLayout() register to bring the cursor into view, so do it here if there is any
		 * possibility that it is needed.
		 */
		if (mLayout.getWidth() > unpaddedWidth || mLayout.getHeight() > unpaddedHeight) {
			registerForPreDraw();
		} else {
			scrollTo(0, 0);
		}

		setMeasuredDimension(width, height);
	}

	@Override
	public boolean onPreDraw() {
		if (mLayout == null) {
			assumeLayout();
		}
		boolean changed = bringTextIntoView();
		getViewTreeObserver().removeOnPreDrawListener(this);
		mPreDrawRegistered = false;
		return !changed;
	}

	@Override
	public void onScreenStateChanged(int screenState) {
		super.onScreenStateChanged(screenState);
		// copied from mEditor.onScreenStateChanged(screenState);
		switch (screenState) {
		case View.SCREEN_STATE_ON:
			resumeBlink();
			break;
		case View.SCREEN_STATE_OFF:
			suspendBlink();
			break;
		}
	}

	@Override
	protected void onScrollChanged(int horiz, int vert, int oldHoriz, int oldVert) {
		super.onScrollChanged(horiz, vert, oldHoriz, oldVert);
		if (mPositionListener != null) {
			mPositionListener.onScrollChanged();
		}
	}

	@Override
	public void onStartTemporaryDetach() {
		super.onStartTemporaryDetach();
		// Only track when onStartTemporaryDetach() is called directly,
		// usually because this instance is an editable field in a list
		if (!mDispatchTemporaryDetach)
			mTemporaryDetach = true;

		// Tell the editor that we are temporarily detached. It can use this to preserve
		// selection state as needed.
		mTemporaryDetach = true;
	}

	public void onTextChanged(CharSequence buffer, int start, int before, int after) {
		// if (DEBUG_EXTRACT)
		// Log.v(LOG_TAG, "onTextChanged start=" + start + " before=" + before + " after=" + after + ": " + buffer);
		// inline from handleTextChanged(buffer, start, before, after);
		// invalidate();
		updateAfterEdit();
		hideCursorControllers();
		if (mIMS.mBatchEditNesting == 0) {
			updateAfterEdit();
		}
		mIMS.mContentChanged = true;
		if (mIMS.mChangedStart < 0) {
			mIMS.mChangedStart = start;
			mIMS.mChangedEnd = start + before;
		} else {
			mIMS.mChangedStart = Math.min(mIMS.mChangedStart, start);
			mIMS.mChangedEnd = Math.max(mIMS.mChangedEnd, start + before - mIMS.mChangedDelta);
		}
		mIMS.mChangedDelta += after - before;
		// inline from sendOnTextChanged(buffer, start, before, after);
		hideCursorControllers();
	}

	@Override
	public boolean onTouchEvent(MotionEvent event) {
		final int action = event.getActionMasked();

		// mEditor.onTouchEvent(event);
		if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
			mLastDownPositionX = event.getX();
			mLastDownPositionY = event.getY();

			// Reset this state; it will be re-set if super.onTouchEvent
			// causes focus to move to the view.
			mTouchFocusSelected = false;
			mIgnoreActionUpEvent = false;
		}

		final boolean superResult = super.onTouchEvent(event);

		/*
		 * Don't handle the release after a long press, because it will move the selection away from whatever the menu
		 * action was trying to affect.
		 */
		if (mDiscardNextActionUp && action == MotionEvent.ACTION_UP) {
			mDiscardNextActionUp = false;
			return superResult;
		}

		final boolean touchIsFinished = (action == MotionEvent.ACTION_UP) && (!mIgnoreActionUpEvent) && isFocused();

		if (mLayout != null) {
			boolean handled = false;
			final boolean textIsSelectable = isTextSelectable();

			if (touchIsFinished) {
				// Show the IME, except when selecting in read-only text.

				viewClicked(mIMM);
				if (!textIsSelectable) {
					handled |= mIMM.showSoftInput(this, 0);
				}

				// The above condition ensures that the mEditor is not null
				// mEditor.onTouchUpEvent(event);
				hideControllers();
				Editable text = getText();
				if (text.length() > 0) {
					// Move cursor
					final int offset = getOffsetForPosition(event.getX(), event.getY());
					Selection.setSelection(text, offset);
					if (hasInsertionController()) {
						getInsertionController().show();
					}
				}

				handled = true;
			}

			if (handled) {
				return true;
			}
		}

		return superResult;
	}

	@Override
	protected void onVisibilityChanged(View changedView, int visibility) {
		super.onVisibilityChanged(changedView, visibility);
		if (visibility != VISIBLE) {
			hideControllers();
		}
	}

	@Override
	public void onWindowFocusChanged(boolean hasWindowFocus) {
		super.onWindowFocusChanged(hasWindowFocus);
		// Copied from Editor#onWindowFocusChanged(hasWindowFocus);
		if (hasWindowFocus) {
			if (mBlink != null) {
				mBlink.uncancel();
				makeBlink();
			}
		} else {
			if (mBlink != null) {
				mBlink.cancel();
			}
			// Order matters! Must be done before onParentLostFocus to rely on isShowingUp
			hideControllers();
			// Don't leave us in the middle of a batch edit. Same as in onFocusChanged
			ensureEndedBatchEdit();
		}
	}

	void prepareCursorControllers() {
		boolean windowSupportsHandles = false;

		ViewGroup.LayoutParams params = getRootView().getLayoutParams();
		if (params instanceof WindowManager.LayoutParams) {
			WindowManager.LayoutParams windowParams = (WindowManager.LayoutParams) params;
			windowSupportsHandles = windowParams.type < WindowManager.LayoutParams.FIRST_SUB_WINDOW
					|| windowParams.type > WindowManager.LayoutParams.LAST_SUB_WINDOW;
		}

		boolean enabled = windowSupportsHandles && getLayout() != null;
		mInsertionControllerEnabled = enabled && isCursorVisible();

		if (!mInsertionControllerEnabled) {
			hideInsertionPointCursorController();
			if (mInsertionPointCursorController != null) {
				mInsertionPointCursorController.onDetached();
				mInsertionPointCursorController = null;
			}
		}
	}

	/**
	 * Prepare text so that there are not zero or two spaces at beginning and end of region defined by [min, max] when
	 * replacing this region by paste. Note that if there were two spaces (or more) at that position before, they are
	 * kept. We just make sure we do not add an extra one from the paste content.
	 */
	long prepareSpacesAroundPaste(int min, int max, CharSequence paste) {
		if (paste.length() > 0) {
			if (min > 0) {
				final char charBefore = mText.charAt(min - 1);
				final char charAfter = paste.charAt(0);

				if (Character.isSpaceChar(charBefore) && Character.isSpaceChar(charAfter)) {
					// Two spaces at beginning of paste: remove one
					final int originalLength = mText.length();
					mText.delete(min - 1, min);
					// Due to filters, there is no guarantee that exactly one character was
					// removed: count instead.
					final int delta = mText.length() - originalLength;
					min += delta;
					max += delta;
				} else if (!Character.isSpaceChar(charBefore) && charBefore != '\n'
						&& !Character.isSpaceChar(charAfter) && charAfter != '\n') {
					// No space at beginning of paste: add one
					final int originalLength = mText.length();
					mText.replace(min, min, " ");
					// Taking possible filters into account as above.
					final int delta = mText.length() - originalLength;
					min += delta;
					max += delta;
				}
			}

			if (max < mText.length()) {
				final char charBefore = paste.charAt(paste.length() - 1);
				final char charAfter = mText.charAt(max);

				if (Character.isSpaceChar(charBefore) && Character.isSpaceChar(charAfter)) {
					// Two spaces at end of paste: remove one
					mText.delete(max, max + 1);
				} else if (!Character.isSpaceChar(charBefore) && charBefore != '\n'
						&& !Character.isSpaceChar(charAfter) && charAfter != '\n') {
					// No space at end of paste: add one
					mText.replace(max, max, " ");
				}
			}
		}

		return TextUtils.packRangeInLong(min, max);
	}

	private void registerForPreDraw() {
		if (!mPreDrawRegistered) {
			getViewTreeObserver().addOnPreDrawListener(this);
			mPreDrawRegistered = true;
		}
	}

	private void resumeBlink() {
		if (mBlink != null) {
			mBlink.uncancel();
			makeBlink();
		}
	}

	boolean selectAllText() {
		final int length = mText.length();
		Selection.setSelection(mText, 0, length);
		return length > 0;
	}

	void sendOnTextChanged(CharSequence text, int start, int before, int after) {
		// mEditor.sendOnTextChanged(start, after);
		// Hide the controllers as soon as text is modified (typing, procedural...)
		// We do not hide the span controllers, since they can be added when a new text is
		// inserted into the text view (voice IME).
		hideCursorControllers();
	}

	/**
	 * Set whether the cursor is visible. The default is true. Note that this property only makes sense for editable
	 * TextView.
	 * 
	 * @see #isCursorVisible()
	 */
	public void setCursorVisible(boolean visible) {
		if (mCursorVisible != visible) {
			mCursorVisible = visible;
			invalidate();

			makeBlink();

			// InsertionPointCursorController depends on mCursorVisible
			prepareCursorControllers();
		}
	}

	/**
	 * Makes the TextView exactly this many ems wide
	 * 
	 * @see #setMaxEms(int)
	 * @see #setMinEms(int)
	 * @see #getMinEms()
	 * @see #getMaxEms()
	 */
	public void setEms(int ems) {
		mMaxWidth = mMinWidth = ems;
		mMaxWidthMode = mMinWidthMode = EMS;

		requestLayout();
		invalidate();
	}

	@Override
	public void setEnabled(boolean enabled) {
		if (enabled == true) {
			return;
		}

		if (!enabled) {
			// Hide the soft input if the currently active TextView is disabled
			if (mIMM.isActive(this)) {
				mIMM.hideSoftInputFromWindow(getWindowToken(), 0);
			}
		}

		super.setEnabled(enabled);

		if (enabled) {
			// Make sure IME is updated with current editor info.
			mIMM.restartInput(this);
		}

		prepareCursorControllers();
		makeBlink();
	}

	/**
	 * Sets the horizontal alignment of the text and the vertical gravity that will be used when there is extra space in
	 * the TextView beyond what is required for the text itself.
	 * 
	 * @see android.view.Gravity
	 */
	public void setGravity(int gravity) {
		if ((gravity & Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK) == 0) {
			gravity |= Gravity.START;
		}
		if ((gravity & Gravity.VERTICAL_GRAVITY_MASK) == 0) {
			gravity |= Gravity.TOP;
		}

		boolean newLayout = false;

		if ((gravity & Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK) != (mGravity & Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK)) {
			newLayout = true;
		}

		if (gravity != mGravity) {
			invalidate();
		}

		mGravity = gravity;

		if (mLayout != null && newLayout) {
			// XXX this is heavy-handed because no actual content changes.
			int want = mLayout.getWidth();
			makeNewLayout(want, true);
		}
	}

	/**
	 * Makes the TextView exactly this many pixels tall. You could do the same thing by specifying this number in the
	 * LayoutParams.
	 * 
	 * Note that setting this value overrides any other (minimum / maximum) number of lines or height setting.
	 */
	public void setHeight(int pixels) {
		mMaximum = mMinimum = pixels;
		mMaxMode = mMinMode = PIXELS;

		requestLayout();
		invalidate();
	}

	/**
	 * Sets the color used to display the selection highlight.
	 */
	public void setHighlightColor(int color) {
		if (mHighlightColor != color) {
			mHighlightColor = color;
			invalidate();
		}
	}

	/**
	 * Sets whether the text should be allowed to be wider than the View is. If false, it will be wrapped to the width
	 * of the View.
	 */
	public void setHorizontallyScrolling(boolean whether) {
		if (mHorizontallyScrolling != whether) {
			mHorizontallyScrolling = whether;

			if (mLayout != null) {
				nullLayouts();
				requestLayout();
				invalidate();
			}
		}
	}

	/**
	 * Set whether the TextView includes extra top and bottom padding to make room for accents that go above the normal
	 * ascent and descent. The default is true.
	 * 
	 * @see #getIncludeFontPadding()
	 */
	public void setIncludeFontPadding(boolean includepad) {
		if (mIncludePad != includepad) {
			mIncludePad = includepad;

			if (mLayout != null) {
				nullLayouts();
				requestLayout();
				invalidate();
			}
		}
	}

	/**
	 * Makes the TextView exactly this many lines tall.
	 * 
	 * Note that setting this value overrides any other (minimum / maximum) number of lines or height setting. A single
	 * line TextView will set this value to 1.
	 */
	public void setLines(int lines) {
		mMaximum = mMinimum = lines;
		mMaxMode = mMinMode = LINES;
		requestLayout();
		invalidate();
	}

	@Override
	public void setPadding(int left, int top, int right, int bottom) {
		if (left != getPaddingLeft() || right != getPaddingRight() || top != getPaddingTop()
				|| bottom != getPaddingBottom()) {
			nullLayouts();
		}

		// the super call will requestLayout()
		super.setPadding(left, top, right, bottom);
		invalidate();
	}

	@Override
	public void setPaddingRelative(int start, int top, int end, int bottom) {
		if (start != getPaddingStart() || end != getPaddingEnd() || top != getPaddingTop()
				|| bottom != getPaddingBottom()) {
			nullLayouts();
		}

		// the super call will requestLayout()
		super.setPaddingRelative(start, top, end, bottom);
		invalidate();
	}

	/**
	 * Sets flags on the Paint being used to display the text and reflows the text if they are different from the old
	 * flags.
	 * 
	 * @see Paint#setFlags
	 */
	public void setPaintFlags(int flags) {
		if (mTextPaint.getFlags() != flags) {
			mTextPaint.setFlags(flags);

			if (mLayout != null) {
				nullLayouts();
				requestLayout();
				invalidate();
			}
		}
	}

	private void setRawTextSize(float size) {
		if (size != mTextPaint.getTextSize()) {
			mTextPaint.setTextSize(size);

			if (mLayout != null) {
				nullLayouts();
				requestLayout();
				invalidate();
			}
		}
	}

	public void setScroller(Scroller s) {
		mScroller = s;
	}

	/**
	 * Gives the text a shadow of the specified radius and color, the specified distance from its normal position.
	 */
	public void setShadowLayer(float radius, float dx, float dy, int color) {
		mTextPaint.setShadowLayer(radius, dx, dy, color);
		mShadowRadius = radius;
		mShadowDx = dx;
		mShadowDy = dy;
		// Will change text clip region
		// // mEditor.invalidateTextDisplayList();
		invalidate();
	}

	public void setText(Editable text) {
		// if (mText != null)
		// throw new IllegalStateException("TextArea does not allow second invocation of setText."
		// + " Editable's methods to manipulate the content.");
		if (text == null)
			return;
		mIMM.restartInput(this);
		// TODO L.A.H. do a replace the content with the content of text, not simply set the value
		// so client should do exactly one setText() then getText() and manipulate the resulting Editable
		mText = text;
		// mText.replace(0, mText.length(), text);
		mText.setSpan(this, 0, mText.length(), Spanned.SPAN_INCLUSIVE_INCLUSIVE);
		final int textLength = text.length();
		if (mLayout != null) {
			checkForRelayout();
		}
		hideCursorControllers(); // inline old sendOnTextChanged(text, 0, oldlen, textLength);
		onTextChanged(text, 0, 0, textLength);
		prepareCursorControllers();
	}

	/**
	 * Sets the text color for all the states (normal, selected, focused) to be this color.
	 * 
	 * @see #setTextColor(ColorStateList)
	 * @see #getTextColors()
	 */
	public void setTextColor(int color) {
		mTextColor = ColorStateList.valueOf(color);
		updateTextColors();
	}

	public void setTextSize(float size) {
		setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
	}

	public void setTextSize(int unit, float size) {
		Context c = getContext();
		Resources r = c == null ? Resources.getSystem() : c.getResources();
		setRawTextSize(TypedValue.applyDimension(unit, size, r.getDisplayMetrics()));
	}

	public void setTypeface(Typeface tf) {
		if (mTextPaint.getTypeface() != tf) {
			mTextPaint.setTypeface(tf);

			if (mLayout != null) {
				nullLayouts();
				requestLayout();
				invalidate();
			}
		}
	}

	public void setTypeface(Typeface tf, int style) {
		if (style > 0) {
			if (tf == null) {
				tf = Typeface.defaultFromStyle(style);
			} else {
				tf = Typeface.create(tf, style);
			}

			setTypeface(tf);
			// now compute what (if any) algorithmic styling is needed
			int typefaceStyle = tf != null ? tf.getStyle() : 0;
			int need = style & ~typefaceStyle;
			mTextPaint.setFakeBoldText((need & Typeface.BOLD) != 0);
			mTextPaint.setTextSkewX((need & Typeface.ITALIC) != 0 ? -0.25f : 0);
		} else {
			mTextPaint.setFakeBoldText(false);
			mTextPaint.setTextSkewX(0);
			setTypeface(tf);
		}
	}

	/**
	 * Makes the TextView exactly this many pixels wide. You could do the same thing by specifying this number in the
	 * LayoutParams.
	 * 
	 * @see #setMaxWidth(int)
	 * @see #setMinWidth(int)
	 * @see #getMinWidth()
	 * @see #getMaxWidth()
	 * 
	 * @attr ref android.R.styleable#TextView_width
	 */
	public void setWidth(int pixels) {
		mMaxWidth = mMinWidth = pixels;
		mMaxWidthMode = mMinWidthMode = PIXELS;
		requestLayout();
		invalidate();
	}

	/**
	 * @return True when the TextView isFocused and has a valid zero-length selection (cursor).
	 */
	private boolean shouldBlink() {
		if (!isCursorVisible() || !isFocused())
			return false;

		final int start = getSelectionStart();
		if (start < 0)
			return false;

		final int end = getSelectionEnd();
		if (end < 0)
			return false;

		return start == end;
	}

	public void onSpanChanged(Spanned buf, Object what, int oldStart, int newStart, int oldEnd, int newEnd) {
		// XXX Make the start and end move together if this ends up spending too much time invalidating.
		boolean selChanged = false;
		int newSelStart = -1, newSelEnd = -1;
		if (what == Selection.SELECTION_END) {
			selChanged = true;
			newSelEnd = newStart;

			if (oldStart >= 0 || newStart >= 0) {
				invalidateCursor(Selection.getSelectionStart(buf), oldStart, newStart);
				checkForResize();
				registerForPreDraw();
				makeBlink();
			}
		}
		if (what == Selection.SELECTION_START) {
			selChanged = true;
			newSelStart = newStart;

			if (oldStart >= 0 || newStart >= 0) {
				int end = Selection.getSelectionEnd(buf);
				invalidateCursor(end, oldStart, newStart);
			}
		}
		if (selChanged) {
			mHighlightPathBogus = true;
			if (!isFocused())
				mSelectionMoved = true;

			if ((buf.getSpanFlags(what) & Spanned.SPAN_INTERMEDIATE) == 0) {
				if (newSelStart < 0) {
					newSelStart = Selection.getSelectionStart(buf);
				}
				if (newSelEnd < 0) {
					newSelEnd = Selection.getSelectionEnd(buf);
				}
				// onSelectionChanged(newSelStart, newSelEnd);
			}
		}
		if (what instanceof UpdateAppearance || what instanceof ParagraphStyle || what instanceof CharacterStyle) {
			if (mIMS.mBatchEditNesting == 0) {
				invalidate();
				mHighlightPathBogus = true;
				checkForResize();
			} else {
				mIMS.mContentChanged = true;
			}
		}
		if (MetaKeyKeyListener.isMetaTracker(buf, what)) {
			mHighlightPathBogus = true;
			// if (MetaKeyKeyListener.isSelectingMetaTracker(buf, what)) {
			// mIMS.mSelectionModeChanged = true;
			// }
			if (Selection.getSelectionStart(buf) >= 0) {
				if (mIMS.mBatchEditNesting == 0) {
					invalidateCursor();
				} else {
					mIMS.mCursorChanged = true;
				}
			}
		}
	}

	private void suspendBlink() {
		if (mBlink != null) {
			mBlink.cancel();
		}
	}

	void updateAfterEdit() {
		invalidate();
		int curs = getSelectionStart();
		if (curs >= 0 || (mGravity & Gravity.VERTICAL_GRAVITY_MASK) == Gravity.BOTTOM) {
			registerForPreDraw();
		}
		checkForResize();
		if (curs >= 0) {
			mHighlightPathBogus = true;
			makeBlink();
			bringPointIntoView(curs);
		}
	}

	private void updateCursorPosition(int cursorIndex, int top, int bottom, float horizontal) {
		if (mCursorDrawable[cursorIndex] == null)
			mCursorDrawable[cursorIndex] = getResources().getDrawable(mCursorDrawableRes);

		if (mTempRect == null)
			mTempRect = new Rect();
		mCursorDrawable[cursorIndex].getPadding(mTempRect);
		final int width = mCursorDrawable[cursorIndex].getIntrinsicWidth();
		horizontal = Math.max(0.5f, horizontal - 0.5f);
		final int left = (int) (horizontal) - mTempRect.left;
		mCursorDrawable[cursorIndex].setBounds(left, top - mTempRect.top, left + width, bottom + mTempRect.bottom);
	}

	void updateCursorsPositions() {
		if (mCursorDrawableRes == 0) {
			mCursorCount = 0;
			return;
		}

		Layout layout = getLayout();

		final int offset = getSelectionStart();
		final int line = layout.getLineForOffset(offset);
		final int top = layout.getLineTop(line);
		final int bottom = layout.getLineTop(line + 1);

		mCursorCount = 1;

		int middle = bottom;
		if (mCursorCount == 2) {
			// Similar to what is done in {@link Layout.#getCursorPath(int, Path, CharSequence)}
			middle = (top + bottom) >> 1;
		}

		updateCursorPosition(0, top, middle, layout.getPrimaryHorizontal(offset));

		if (mCursorCount == 2) {
			updateCursorPosition(1, middle, bottom, layout.getSecondaryHorizontal(offset));
		}
	}

	private void updateTextColors() {
		boolean inval = false;
		int color = mTextColor.getColorForState(getDrawableState(), 0);
		if (color != mCurTextColor) {
			mCurTextColor = color;
			inval = true;
		}
		if (mLinkTextColor != null) {
			color = mLinkTextColor.getColorForState(getDrawableState(), 0);
			if (color != mTextPaint.linkColor) {
				mTextPaint.linkColor = color;
				inval = true;
			}
		}
		if (inval) {
			// Text needs to be redrawn with the new color
			invalidate();
		}
	}

	protected void viewClicked(InputMethodManager imm) {
		if (imm != null) {
			imm.viewClicked(this);
		}
	}

	int viewportToContentHorizontalOffset() {
		return getCompoundPaddingLeft() - getScrollX();
	}

	int viewportToContentVerticalOffset() {
		int offset = getExtendedPaddingTop() - getScrollY();
		if ((mGravity & Gravity.VERTICAL_GRAVITY_MASK) != Gravity.TOP) {
			offset += getVerticalOffset(false);
		}
		return offset;
	}

	@Override
	public void afterTextChanged(Editable arg0) {
	}

	@Override
	public void beforeTextChanged(CharSequence arg0, int arg1, int arg2, int arg3) {
	}

}
