define(["util/notification"],
		function(notification){
    var userFunctions = {
        init: function () {}
    };
    userFunctions.fetchUsers = function (object, callback) {
        var failureMessage = "Failed to load users.";
        $.ajax({
            url: window.location.origin + "/auth/user",
            type: 'GET',
            contentType: 'application/json',
            success: function(response){
                callback(response, object);
            }.bind(object),
            error: function(response){
                handleAjaxError(response, failureMessage);
            }
        });
    }.bind(userFunctions);

    userFunctions.showUserDetails = function (uuid, callback) {
        var failureMessage = "Failed to load user details.";
        $.ajax({
            url: window.location.origin + "/auth/user/" + uuid,
            type: 'GET',
            contentType: 'application/json',
            success: function(response){
                callback(response);
            },
            error: function(response){
                handleAjaxError(response, failureMessage);
            }
        });
    }.bind(userFunctions);

    userFunctions.createOrUpdateUser = function (user, requestType, callback) {
        var successMessage = requestType == 'POST' ? 'User created' : 'User updated';
        var failureMessage = requestType == 'POST' ? 'Failed to create user' : 'Failed to update user';
        $.ajax({
            url: window.location.origin + '/auth/user',
            type: requestType,
            contentType: 'application/json',
            data: JSON.stringify(user),
            success: function(response){
                notification.showSuccessMessage(successMessage);
                callback(response);
            }.bind(this),
            error: function(response){
                handleAjaxError(response, failureMessage);
            }
        });
    }.bind(userFunctions);

    userFunctions.deleteUser = function (uuid, callback) {
        var failureMessage = 'Failed to delete user';
        $.ajax({
            url: window.location.origin + '/auth/user/' + uuid,
            type: 'DELETE',
            contentType: 'application/json',
            success: function(response){
                notification.showSuccessMessage('User deleted');
                callback(response);
            },
            error: function(response){
                handleAjaxError(response, failureMessage);
            }
        });
    }.bind(userFunctions);

    userFunctions.getAvailableRoles = function (callback) {
        var failureMessage = 'Failed to load roles';
        $.ajax({
            url: window.location.origin + "/auth/role",
            type: 'GET',
            contentType: 'application/json',
            success: function(response){
                callback(response);
            },
            error: function(response){
                console.log(failureMessage);
                handleAjaxError(response, failureMessage);
            }
        });
    }.bind(userFunctions);

    var handleAjaxError = function (response, message) {
        if (response.status !== 401) {
            notification.showFailureMessage(message);
        }
    }.bind(userFunctions);

	return userFunctions;
});