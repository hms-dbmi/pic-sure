define(["util/notification", "picSure/settings"],
		function(notification, settings){
    var userFunctions = {
        init: function () {}
    };
    userFunctions.fetchUsers = function (object, callback) {
        var failureMessage = "Failed to load users. ";
        $.ajax({
            url: window.location.origin + settings.basePath + '/user',
            type: 'GET',
            contentType: 'application/json',
            success: function(response){
                callback(response, object);
            }.bind(object),
            error: function(response){
                handleAjaxError(response, failureMessage + (response.responseJSON.message?response.message:""), 6000);
            }
        });
    }.bind(userFunctions);

    userFunctions.showUserDetails = function (uuid, callback) {
        var failureMessage = 'Failed to load user details. ';
        $.ajax({
            url: window.location.origin + settings.basePath + '/user/' + uuid,
            type: 'GET',
            contentType: 'application/json',
            success: function(response){
                callback(response);
            },
            error: function(response){
                handleAjaxError(response, failureMessage + (response.responseJSON.message?response.message:""), 6000);
            }
        });
    }.bind(userFunctions);

    userFunctions.createOrUpdateUser = function (user, requestType, callback) {
        var successMessage = requestType == 'POST' ? 'User created. ' : 'User updated. ';
        var failureMessage = requestType == 'POST' ? 'Failed to create user. ' : 'Failed to update user. ';
        $.ajax({
            url: window.location.origin + settings.basePath + '/user',
            type: requestType,
            contentType: 'application/json',
            data: JSON.stringify(user),
            success: function(response){
                notification.showSuccessMessage(successMessage);
                callback(response);
            }.bind(this),
            error: function(response){
                handleAjaxError(response, failureMessage + (response.responseJSON.message?response.responseJSON.message:""), 9000);
            }
        });
    }.bind(userFunctions);

    userFunctions.getAvailableRoles = function (callback) {
        var failureMessage = 'Failed to load roles. ';
        $.ajax({
            url: window.location.origin + settings.basePath + '/role',
            type: 'GET',
            contentType: 'application/json',
            success: function(response){
                callback(response);
            },
            error: function(response){
                console.log(failureMessage);
                handleAjaxError(response, failureMessage + (response.responseJSON.message?response.message:""), 6000);
            }
        });
    }.bind(userFunctions);

    userFunctions.me = function (object, callback) {
        $.ajax({
            url: window.location.origin + settings.basePath + '/user/me',
            type: 'GET',
            contentType: 'application/json',
            success: function(response){
                callback(response, object);
            }.bind(object),
            error: function(response){
            }
        });
    }.bind(userFunctions);

    userFunctions.meWithToken = function (object, callback) {
        $.ajax({
            url: window.location.origin + settings.basePath + '/user/me?hasToken',
            type: 'GET',
            contentType: 'application/json',
            success: function(response){
                callback(response, object);
            }.bind(object),
            error: function(response){
            }
        });
    }.bind(userFunctions);

    userFunctions.refreshUserLongTermToken = function (object, callback) {
        $.ajax({
            url: window.location.origin + settings.basePath + '/user/me/refresh_long_term_token',
            type: 'GET',
            contentType: 'application/json',
            success: function(response){
                callback(response, object);
            }.bind(object),
            error: function(response){
            }
        });
    }.bind(userFunctions);

    var handleAjaxError = function (response, message, timeout) {
        if (response.status !== 401) {
            notification.showFailureMessage(message, timeout);
        }
    }.bind(userFunctions);

	return userFunctions;
});