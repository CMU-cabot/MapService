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
<link rel="stylesheet" href="js/lib/fontawesome-free-6.5.1-web/css/all.min.css">
<script type="text/javascript" src="jquery/jquery-1.11.3.min.js"></script>
<script type="text/javascript" src="js/messages.js"></script>
<script src="openlayers/v4.6.5/ol.js"></script>
<script type="text/javascript" src="js/hokoukukan.js"></script>
<script type="text/javascript" src="js/util.js"></script>
<script type="text/javascript" src="js/maps.js"></script>
<script type="text/javascript" src="js/FloorPlanOverlay.js"></script>
<script type="text/javascript" src="js/indoor.js"></script>
<script type="text/javascript" src="js/tour-editor.js"></script>
<script type="text/javascript" src="js/tour-message-editor.js"></script>
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
	--r1: 10em;
	--r2: 25%;
	--r3: 25%;
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
.row3 {
	height: var(--r3);
	margin-bottom: var(--gap);
}
.bottom {
	top: calc(var(--r0) + var(--r1) + var(--r2) + var(--r3) + var(--gap) * 2);
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
	width: 1px;
}
table table tbody > tr > td:last-child {
	border-right: 0px;
}
table table tr {
	background-color: #eee;
}
table table td[contenteditable=true] {
	background-color: #fff;
}
#dest_properties table.destination th {
	background-color: lightskyblue;
}
#tour_properties table.tour th {
	background-color: lightpink;
}
#menu {
	position: absolute;
	margin: 0px;
	padding: 0px;
	min-width: 200px;
	line-height: 12pt;
	font-size: 9pt;
	background-color: #efe;
	border: 1pt solid #666;
	box-shadow: 2px 2px 4px 0px #666;
	color: #000;
}
#menu li {
	list-style: none;
	cursor: pointer;
	padding: 2px;
}
#menu li.separator {
	border-bottom: 1px solid #666;
}
#menu li:hover {
	background-color: #fee;
}
.destination_selected {
	font-weight: bold;
}

.disabled-icon {
    opacity: 0.25; /* Half opacity */
    cursor: default !important;
}

#message-edit {
	display: none;
	z-index: 1;
	position: fixed;
	background-color: #0004;
	left: 0;
	top: 0;
	right: 0;
	bottom: 0;
}

#message-form {
	position: fixed;
	overflow: auto;
	background-color: #FFF;
	font-size: 9pt;
	left: 100px;
	top: 20px;
	right: 20px;
	bottom: 20px;
	padding: 10px;
	border: 1px solid;
	box-shadow: 5px 5px 5px;
}

.message {
	margin-bottom: 2em;
}

.message input {
	width: 100%;
	box-sizing: border-box;
	border: none;
}

table.message th {
	background-color: lightskyblue;
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
			|
			<a href="./editor.jsp">Editor</a>
		</div>
	</div>
	<div class="left row1 scroll">
		<div id="list" class="inner"></div>
	</div>
	<div class="left row2 scroll">
		<div id="tour_list" class="inner"></div>
	</div>
	<div class="left row3 scroll">
		<div id="tour_properties" class="inner"></div>
	</div>
	<div class="left bottom scroll">
		<div id="dest_properties" class="inner"></div>
	</div>
	<div id="map" class="ui-page-theme-a"></div>
	<div id="message-edit">
		<div id="message-form">
			<h2 id="messages_title">Message Editor</h2>
			<datalist id="message_types">
				<option value="summary">summary</option>
				<option value="startMessage">startMessage</option>
				<option value="arriveMessage">arriveMessage</option>
			</datalist>
			<div id="messages"></div>
			<div>
				<button id="save_messages">OK</button>
				<button id="cancel_messages">Cancel</button>
			</div>
		</div>
	</div>
</body>
</html>