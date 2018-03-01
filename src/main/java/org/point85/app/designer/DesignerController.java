package org.point85.app.designer;

import java.util.HashMap;
import java.util.Map;

// base controller class
public abstract class DesignerController {

	// Reference to the main application
	private DesignerApplication app;

	protected DesignerApplication getApp() {
		return this.app;
	}

	public void setApp(DesignerApplication app) {
		this.app = app;
	}
}
