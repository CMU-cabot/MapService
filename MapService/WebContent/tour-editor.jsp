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
<style>
:root {
	--left: 500px;
	--r0: 2em;
	--r1: 25%;
	--r2: 25%;
	--gap: 10px;
}
.left {
	width: var(--left);
}
#map {
	left: var(--left);
}
.row0 {
	height: var(--r0);
}
.row1 {
	height: var(--r1);
	margin-bottom: var(--gap);
}
.row2 {
	height: var(--r2);
	margin-bottom: var(--gap);
}
.bottom {
	top: calc(var(--r0) + var(--r1) + var(--r2) + var(--gap) * 2);
}
#tour_list table th {
	background-color: lightpink;
}
table td:has(table) {
	padding: 0px;
}
table table {
	border: 0px;
}
table table > tbody > tr:first-child > td {
	border-top: 0px;
}
table table > tbody > tr:last-child > td {
	border-bottom: 0px;
}
table table > tbody > tr > td:first-child {
	border-left: 0px;
}
table table tbody > tr > td:last-child {
	border-right: 0px;
}
</style>
<title>Tour Editor</title>
</head>
<body>
	<div class="left row0">
		<div id="help" class="inner">
			<%=user%> <a href="tour-editor.jsp?logout=true">Log out</a>
			| Raw data <a href="cabot/tourdata.json" download="tourdata.json">download</a>
			<a href="javascript:void(0)" id="upload_link">upload</a>
			<input type="file" accept=".json" id="upload_file" style="display: none;">
		</div>
	</div>
	<div class="left row1 scroll">
		<div id="list" class="inner"></div>
	</div>
	<div class="left row2 scroll">
		<div id="tour_list" class="inner"></div>
	</div>
	<div class="left bottom scroll">
		<div id="properties" class="inner"></div>
	</div>
	<div id="map" class="ui-page-theme-a"></div>
</body>
</html>