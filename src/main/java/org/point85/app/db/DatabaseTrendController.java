package org.point85.app.db;

import java.time.OffsetDateTime;
import java.util.List;

import org.point85.app.AppUtils;
import org.point85.app.FXMLLoaderFactory;
import org.point85.app.ImageManager;
import org.point85.app.Images;
import org.point85.app.charts.DataSubscriber;
import org.point85.app.charts.TrendChartController;
import org.point85.app.designer.DesignerDialogController;
import org.point85.domain.db.DatabaseEvent;
import org.point85.domain.db.DatabaseEventClient;
import org.point85.domain.db.DatabaseEventListener;
import org.point85.domain.db.DatabaseEventSource;
import org.point85.domain.db.DatabaseEventStatus;
import org.point85.domain.script.EventResolver;

import javafx.application.Platform;
import javafx.concurrent.Service;
import javafx.concurrent.Task;
import javafx.concurrent.WorkerStateEvent;
import javafx.event.EventHandler;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;

public class DatabaseTrendController extends DesignerDialogController implements DatabaseEventListener, DataSubscriber {

	// polling client
	private DatabaseEventClient databaseClient;

	// trend chart
	private TrendChartController trendChartController;

	// trend chart pane
	private SplitPane spTrendChart;

	@FXML
	private Label lbSourceId;

	@FXML
	private Label lbUser;

	@FXML
	private TextField tfTestValue;

	@FXML
	private Button btTest;

	public SplitPane initializeTrend() throws Exception {
		if (trendChartController == null) {
			// Load the fxml file and create the anchor pane
			FXMLLoader loader = FXMLLoaderFactory.trendChartLoader();
			spTrendChart = (SplitPane) loader.getRoot();

			trendChartController = loader.getController();
			trendChartController.initialize(getApp());

			// data provider
			trendChartController.setProvider(this);

			setImages();

			lbUser.setText("");
		}
		return spTrendChart;
	}

	// images for buttons
	@Override
	protected void setImages() throws Exception {
		super.setImages();

		// write test
		btTest.setGraphic(ImageManager.instance().getImageView(Images.EXECUTE));
		btTest.setContentDisplay(ContentDisplay.LEFT);
	}

	public void setEventResolver(EventResolver eventResolver) throws Exception {
		eventResolver.setWatchMode(true);
		trendChartController.setEventResolver(eventResolver);

		lbSourceId.setText(
				"Equipment: " + eventResolver.getEquipment().getName() + ", Source Id: " + eventResolver.getSourceId());

		lbUser.setText("User: " + eventResolver.getDataSource().getUserName());
	}

	@Override
	@FXML
	protected void onOK() {
		super.onOK();
		try {
			unsubscribeFromDataSource();
		} catch (Exception e) {
			AppUtils.showErrorDialog(e);
		}
	}

	@Override
	@FXML
	protected void onCancel() {
		super.onCancel();

		try {
			unsubscribeFromDataSource();
		} catch (Exception e) {
			AppUtils.showErrorDialog(e);
		}
	}

	@Override
	public boolean isSubscribed() {
		return databaseClient != null ? true : false;
	}

	@Override
	public void subscribeToDataSource() throws Exception {
		if (databaseClient != null) {
			return;
		}

		DatabaseEventSource source = (DatabaseEventSource) trendChartController.getEventResolver().getDataSource();
		int period = trendChartController.getEventResolver().getUpdatePeriod();
		String sourceId = trendChartController.getEventResolver().getSourceId();

		databaseClient = new DatabaseEventClient(this, period);
		databaseClient.setSourceId(sourceId);
		databaseClient.connectToServer(source.getHost(), source.getUserName(), source.getUserPassword());

		// add to context
		getApp().getAppContext().addDatabaseEventClient(databaseClient);

		// start the trend
		// trendChartController.onStartTrending();
		trendChartController.enableTrending(true);

		// start polling for events
		databaseClient.startPolling();
	}

	@Override
	public void unsubscribeFromDataSource() throws Exception {
		if (databaseClient == null) {
			return;
		}
		databaseClient.disconnect();

		// remove from app context
		getApp().getAppContext().removeDatabaseEventClient(databaseClient);
		databaseClient = null;

		// stop the trend
		trendChartController.onStopTrending();
	}

	@Override
	public void resolveDatabaseEvents(DatabaseEventClient databaseClient, List<DatabaseEvent> events) {
		for (DatabaseEvent event : events) {
			handleDatabaseEvent(databaseClient, event);
		}
	}

	private void handleDatabaseEvent(DatabaseEventClient databaseClient, DatabaseEvent databaseEvent) {
		ResolutionService service = new ResolutionService(databaseClient, databaseEvent);

		service.setOnFailed(new EventHandler<WorkerStateEvent>() {
			@Override
			public void handle(WorkerStateEvent event) {
				Throwable t = event.getSource().getException();

				if (t != null) {
					// connection failed
					AppUtils.showErrorDialog(t.getMessage());
				}
			}
		});

		// run on application thread
		service.start();
	}

	@FXML
	private void onTest() {
		try {
			if (databaseClient == null) {
				throw new Exception("The trend is not connected to a database server.");
			}

			EventResolver eventResolver = trendChartController.getEventResolver();

			String inputValue = tfTestValue.getText();
			String sourceId = eventResolver.getSourceId();

			// write to the interface table
			DatabaseEvent databaseEvent = new DatabaseEvent();
			databaseEvent.setSourceId(sourceId);
			databaseEvent.setInputValue(inputValue);
			databaseEvent.setTime(OffsetDateTime.now());

			DatabaseEvent saved = databaseClient.save(databaseEvent);
			databaseEvent = saved;
		} catch (Exception e) {
			AppUtils.showErrorDialog(e);
		}
	}

	// service class for callbacks on received events
	private class ResolutionService extends Service<Void> {
		private final DatabaseEventClient dbClient;
		private final DatabaseEvent databaseEvent;

		public ResolutionService(DatabaseEventClient databaseClient, DatabaseEvent databaseEvent) {
			this.dbClient = databaseClient;
			this.databaseEvent = databaseEvent;
		}

		@Override
		protected Task<Void> createTask() {
			return new Task<Void>() {

				@Override
				protected Void call() {
					try {
						trendChartController.invokeResolver(getApp().getAppContext(), databaseEvent.getInputValue(),
								databaseEvent.getTime());

						databaseEvent.setStatus(DatabaseEventStatus.PASS);
						databaseEvent.setError(null);
					} catch (Exception e) {
						// record the error
						databaseEvent.setStatus(DatabaseEventStatus.FAIL);
						databaseEvent.setError(e.getMessage());

						Platform.runLater(() -> {
							AppUtils.showErrorDialog(e);
						});
					} finally {
						try {
							dbClient.save(databaseEvent);
						} catch (Exception ex) {
							// ignore
						}
					}
					return null;
				}
			};
		}
	}
}