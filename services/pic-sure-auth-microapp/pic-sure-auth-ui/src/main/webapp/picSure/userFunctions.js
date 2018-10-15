define(["util/notification"],
		function(notification){
    var userFunctions = {
        init: function () {}
    };
    userFunctions.fetchUsers = function (object, callback) {
        $.ajax({
            url: window.location.origin + "/auth/user",
            type: 'GET',
            contentType: 'application/json',
            success: function(response){
                callback(response, object);
            }.bind(object),
            error: function(response){
                notification.showFailureMessage("Failed to load users.");
            }
        });
    }.bind(userFunctions);

    userFunctions.showUserDetails = function (uuid, callback) {
        $.ajax({
            url: window.location.origin + "/auth/user/" + uuid,
            type: 'GET',
            contentType: 'application/json',
            success: function(response){
                callback(response);
            },
            error: function(response){
                notification.showFailureMessage("Failed to load user details.");
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
                notification.showSuccessMessage(failureMessage);
            }
        });
    }.bind(userFunctions);

    userFunctions.deleteUser = function (uuid, callback) {
        $.ajax({
            url: window.location.origin + '/auth/user/' + uuid,
            type: 'DELETE',
            contentType: 'application/json',
            success: function(response){
                notification.showSuccessMessage('User deleted');
                callback(response);
            },
            error: function(response){
                notification.showFailureMessage('Failed to delete user');
            }
        });
    }.bind(userFunctions);

    userFunctions.getAvailableRoles = function (callback) {
        $.ajax({
            url: window.location.origin + "/auth/user/availableRoles",
            type: 'GET',
            contentType: 'application/json',
            success: function(response){
                callback(response);
            },
            error: function(response){
                console.log('Failed to load roles');
            }
        });
    }.bind(userFunctions);

	return userFunctions;
});