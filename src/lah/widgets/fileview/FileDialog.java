package lah.widgets.fileview;

import java.io.File;

import lah.widgets.fileview.FileListView.FileSelectListener;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Environment;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;

/**
 * A reusable file/directory {@link ListView}.
 * 
 * In TeXPortal project, {@link FileListView} allow users to
 * <ul>
 * <li>choose a TeX or LaTeX source (input) file; and</li>
 * <li>choose a local TeX package repository.</li>
 * <li>[some time in the future] choose a local installation location.</li>
 * </ul>
 * 
 * TODO privatize the original {@link AlertDialog} methods
 * 
 * @author Vu An Hoa
 * 
 */
public class FileDialog extends AlertDialog implements
		FileListView.FileSelectListener,
		android.content.DialogInterface.OnClickListener {

	/**
	 * The current file selected
	 */
	private File current_file_selected;

	/**
	 * An {@link EditText}
	 */
	private EditText current_selection;

	/**
	 * The {@link FileListView} that list files in the current directory
	 */
	private FileListView file_browse;

	/**
	 * The {@link FileSelectListener} to update when the user click on 'Select'
	 * to select a file
	 */
	private FileListView.FileSelectListener listener;

	/**
	 * Construct a dialog and register a listener who will get notified of the
	 * selected file
	 * 
	 * @param context
	 * @param listener
	 */
	public FileDialog(Context context, FileListView.FileSelectListener listener) {
		super(context);
		this.listener = listener;
		initializeView();
	}

	/**
	 * Override the {@link AlertDialog}'s dismiss to prevent dialog closing
	 * after one of the buttons is clicked. The dismissal of the dialog is
	 * controlled by onClick().
	 */
	@Override
	public void dismiss() {
	}

	void initializeView() {
		File initial_directory = new File(Environment
				.getExternalStorageDirectory().getPath());

		LinearLayout dialog_layout = new LinearLayout(getContext());
		dialog_layout.setOrientation(LinearLayout.VERTICAL);

		current_selection = new EditText(getContext());
		current_selection.setTextSize(16);
		current_selection.setText(initial_directory.getAbsolutePath());
		current_selection.setFocusable(false);

		file_browse = new FileListView(getContext(), this, initial_directory);

		dialog_layout.addView(current_selection);
		dialog_layout.addView(file_browse);

		setView(dialog_layout);
		setButton(BUTTON_NEGATIVE, "Cancel", this);
		setButton(BUTTON_NEUTRAL, "Up", this);
		setButton(BUTTON_POSITIVE, "Select", this);
	}

	/**
	 * Process when the user click a button ('Cancel' or 'Select')
	 */
	public void onClick(DialogInterface dialog, int which) {
		switch (which) {
		case BUTTON_NEGATIVE:
			current_file_selected = null;
			super.dismiss();
			break;
		case BUTTON_NEUTRAL:
			file_browse.goUp();
			break;
		case BUTTON_POSITIVE:
		default:
			// notify the listener about the selected file or directory
			if (listener != null)
				listener.onFileSelected(current_file_selected);
			super.dismiss();
			break;
		}
	}

	/**
	 * Process the update of current directory
	 */
	public void onFileSelected(File result) {
		current_file_selected = file_browse.getSelectedFile();
		current_selection.setText(result.getAbsolutePath());
	}

}