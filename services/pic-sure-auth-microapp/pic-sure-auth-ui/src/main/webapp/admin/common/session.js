define(["jquery", "underscore"], function($, _){
	var storedSession = JSON.parse(
			sessionStorage.getItem("session"));
	
	var session = storedSession ? storedSession : {
		username : null,
		permissions : []
	};
	
	var configureAjax = function(callback){
		$.ajaxSetup({
			headers: {"Authorization": "Bearer " + session.token},
			statusCode: {
				401: function(){
                    callback();
				},
				403: function(){
                    history.pushState({}, "", "tos");
				}
			}
		});
	};
	
	return {
		username : session.username,
		may : function(permission){
			return _.contains(permission, session.permissions);
		},
		authenticated : function(userId, token, username, permissions, acceptedTOS, callback){
			session.userId = userId;
			session.token = token;
			session.username = username;
			session.permissions = permissions;
			session.acceptedTOS = acceptedTOS;
			sessionStorage.setItem("session", JSON.stringify(session));
			configureAjax(callback);
		},
		isValid : function(callback){
			if(session.username){
				configureAjax(callback);
				return session.username;
			}else{
				return false;
			}
		},
		userEmail : function(){
			return JSON.parse(sessionStorage.session).username;
		},
		userId : function(){
			return JSON.parse(sessionStorage.session).userId;
		},
		userMode : function(){
			return JSON.parse(sessionStorage.session).currentUserMode;
		},
		acceptedTOS : function(){
			return JSON.parse(sessionStorage.session).acceptedTOS;
		},
		activity : _.throttle(function(activity){
			if(typeof activity !== "string"){
				activity = window.location.href;
			}
			$.ajax({
				data: JSON.stringify({
					description : activity
				}),
				url: "/rest/interaction",
				type: 'POST',
				dataType: "json",
				contentType: "application/json"
			});
		}, 10000),
        loadSessionVariables : function(callback){
            $.ajax({
				url: window.location.origin + "/auth/connection",
				type: 'GET',
				contentType: 'application/json',
				success: function(response){
                    sessionStorage.setItem("connections", JSON.stringify(response));
                    callback();
                }.bind(this),
				error: function(response){
					console.log("Failed to load connections from the server. Using defaults instead.");
                    callback();
				}
			});
        },
		setAcceptedTOS : function() {
			session.acceptedTOS = true;
            sessionStorage.setItem("session", JSON.stringify(session));
		}
	}
});