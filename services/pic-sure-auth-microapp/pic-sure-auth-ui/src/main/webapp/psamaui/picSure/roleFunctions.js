define(["util/notification", "picSure/settings"],
		function(notification, settings){
    var roleFunctions = {
        init: function () {}
    };
    roleFunctions.fetchRoles = function (object, callback) {
        $.ajax({
            url: window.location.origin + settings.basePath + '/role',
            type: 'GET',
            contentType: 'application/json',
            success: function(response){
                callback(response, object);
            }.bind(object),
            error: function(response){
                notification.showFailureMessage("Failed to load roles.");
            }
        });
    }.bind(roleFunctions);

    roleFunctions.showRoleDetails = function (uuid, callback) {
        $.ajax({
            url: window.location.origin + settings.basePath + '/role/' + uuid,
            type: 'GET',
            contentType: 'application/json',
            success: function(response){
                callback(response);
            },
            error: function(response){
                notification.showFailureMessage("Failed to load role details.");
            }
        });
    }.bind(roleFunctions);

    roleFunctions.createOrUpdateRole = function (role, requestType, callback) {
        var successMessage = requestType == 'POST' ? 'Role created' : 'Role updated';
        var failureMessage = requestType == 'POST' ? 'Failed to create role' : 'Failed to update role';
        $.ajax({
            url: window.location.origin + settings.basePath + '/role',
            type: requestType,
            contentType: 'application/json',
            data: JSON.stringify(role),
            success: function(response){
                notification.showSuccessMessage(successMessage);
                callback(response);
            }.bind(this),
            error: function(response){
                notification.showFailureMessage(failureMessage);
            }
        });
    }.bind(roleFunctions);

    roleFunctions.deleteRole = function (uuid, callback) {
        $.ajax({
            url: window.location.origin + settings.basePath + '/role/' + uuid,
            type: 'DELETE',
            contentType: 'application/json',
            success: function(response){
                notification.showSuccessMessage('Role deleted');
                callback(response);
            },
            error: function(response){
                notification.showFailureMessage('Failed to delete role');
            }
        });
    }.bind(roleFunctions);

	return roleFunctions;
});