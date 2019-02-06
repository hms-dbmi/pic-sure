define(["util/notification", "picSure/settings"],
		function(notification, settings){
    var privilegeFunctions = {
        init: function () {}
    };
    privilegeFunctions.fetchPrivileges = function (object, callback) {
        $.ajax({
            url: window.location.origin + settings.basePath + '/privilege',
            type: 'GET',
            contentType: 'application/json',
            success: function(response){
                callback(response, object);
            }.bind(object),
            error: function(response){
                notification.showFailureMessage("Failed to load privileges.");
            }
        });
    }.bind(privilegeFunctions);

    privilegeFunctions.showPrivilegeDetails = function (uuid, callback) {
        $.ajax({
            url: window.location.origin + settings.basePath + '/privilege/' + uuid,
            type: 'GET',
            contentType: 'application/json',
            success: function(response){
                callback(response);
            },
            error: function(response){
                notification.showFailureMessage("Failed to load privilege details.");
            }
        });
    }.bind(privilegeFunctions);

    privilegeFunctions.createOrUpdatePrivilege = function (privilege, requestType, callback) {
        var successMessage = requestType == 'POST' ? 'Privilege created' : 'Privilege updated';
        var failureMessage = requestType == 'POST' ? 'Failed to create privilege' : 'Failed to update privilege';
        $.ajax({
            url: window.location.origin + settings.basePath + '/privilege',
            type: requestType,
            contentType: 'application/json',
            data: JSON.stringify(privilege),
            success: function(response){
                notification.showSuccessMessage(successMessage);
                callback(response);
            }.bind(this),
            error: function(response){
                notification.showFailureMessage(failureMessage);
            }
        });
    }.bind(privilegeFunctions);

    privilegeFunctions.deletePrivilege = function (uuid, callback) {
        $.ajax({
            url: window.location.origin + settings.basePath + '/privilege/' + uuid,
            type: 'DELETE',
            contentType: 'application/json',
            success: function(response){
                notification.showSuccessMessage('Privilege deleted');
                callback(response);
            },
            error: function(response){
                notification.showFailureMessage('Failed to delete privilege');
            }
        });
    }.bind(privilegeFunctions);

	return privilegeFunctions;
});