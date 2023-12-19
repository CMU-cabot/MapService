<%@page import="org.apache.wink.json4j.JSONArray"%>
<%@page import="org.apache.wink.json4j.JSONObject"%>
<jsp:useBean id="authBean" scope="request"
	class="hulop.hokoukukan.bean.AuthBean" />
<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<%
	if (!authBean.supportRole("editor")) {
		response.sendError(HttpServletResponse.SC_FORBIDDEN);
		return;
	}
	Object profile = authBean.getProfile(request);
	if (profile == null || !authBean.hasRole(request, "editor")) {
		response.sendRedirect("login.jsp?logout=true&redirect_url=tour-editor.jsp");
		return;
	}
	String user =  ((JSONObject) profile).getString("user");
%>
<!DOCTYPE html PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN" "http://www.w3.org/TR/html4/loose.dtd">
<html>
<head>
<meta name="copyright" content="Copyright (c) IBM Corporation and others 2014, 2017. This page is made available under MIT license.">
<meta charset="UTF-8">
<link rel="stylesheet" href="css/editor.css">
<link rel="stylesheet" href="css/ol3.css">
<link rel="stylesheet" href="openlayers/v4.6.5/ol.css">
<link rel="stylesheet" href="jquery/jquery.mobile-1.4.5.min.css"/>
<script type="text/javascript" src="jquery/jquery-1.11.3.min.js"></script>
<script type="text/javascript" src="js/messages.js"></script>
<script src="openlayers/v4.6.5/ol.js"></script>
<script type="text/javascript" src="js/hokoukukan.js"></script>
<script type="text/javascript" src="js/util.js"></script>
<script type="text/javascript" src="js/maps.js"></script>
<script type="text/javascript" src="js/FloorPlanOverlay.js"></script>
<script type="text/javascript" src="js/indoor.js"></script>
<script type="text/javascript" src="js/tour-editor.js"></script>
<script type="text/javascript" src="js/login-monitor.js"></script>
<script type="text/javascript">
	$(document).ready(function(){
		console.log("Map init");
		$hulop.map.init();
	});
</script>
<title>Tour Editor</title>
</head>
<body>
	<div class="left row1 scroll">
		<div id="help" class="inner">
			<%=user%> <a href="tour-editor.jsp?logout=true">Log out</a>
			| Raw data <a href="cabot/tourdata.json" download="tourdata.json">download</a> | <button id="upload_button">upload</button>
			<input type="file" accept=".json" id="upload_file" style="display: none;">
		</div>
		<div id="list" class="inner"></div>
	</div>
	<div class="left row2 scroll"></div>
	<div class="left bottom scroll">
		<div id="properties" class="inner"></div>
	</div>
	<div id="map" class="ui-page-theme-a"></div>
</body>
</html>