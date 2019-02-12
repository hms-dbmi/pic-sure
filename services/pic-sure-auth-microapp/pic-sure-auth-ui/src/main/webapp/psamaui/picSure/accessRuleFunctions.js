define(["util/notification", "picSure/settings"],
		function(notification, settings){
    var accessRuleFunctions = {
        init: function () {}
    };
    accessRuleFunctions.fetchAccessRules = function (object, callback) {
        $.ajax({
            url: window.location.origin + settings.basePath + '/accessRule',
            type: 'GET',
            contentType: 'application/json',
            success: function(response){
                callback(response, object);
            }.bind(object),
            error: function(response){
                notification.showFailureMessage("Failed to load accessRules.");
            }
        });
    }.bind(accessRuleFunctions);

    accessRuleFunctions.showAccessRuleDetails = function (uuid, callback) {
        $.ajax({
            url: window.location.origin + settings.basePath + '/accessRule/' + uuid,
            type: 'GET',
            contentType: 'application/json',
            success: function(response){
                callback(response);
            },
            error: function(response){
                notification.showFailureMessage("Failed to load accessRule details.");
            }
        });
    }.bind(accessRuleFunctions);

    accessRuleFunctions.createOrUpdateAccessRule = function (accessRule, requestType, callback) {
        var successMessage = requestType == 'POST' ? 'AccessRule created' : 'AccessRule updated';
        var failureMessage = requestType == 'POST' ? 'Failed to create accessRule' : 'Failed to update accessRule';
        $.ajax({
            url: window.location.origin + settings.basePath + '/accessRule',
            type: requestType,
            contentType: 'application/json',
            data: JSON.stringify(accessRule),
            success: function(response){
                notification.showSuccessMessage(successMessage);
                callback(response);
            }.bind(this),
            error: function(response){
                notification.showFailureMessage(failureMessage);
            }
        });
    }.bind(accessRuleFunctions);

    accessRuleFunctions.deleteAccessRule = function (uuid, callback) {
        $.ajax({
            url: window.location.origin + settings.basePath + '/accessRule/' + uuid,
            type: 'DELETE',
            contentType: 'application/json',
            success: function(response){
                notification.showSuccessMessage('AccessRule deleted');
                callback(response);
            },
            error: function(response){
                notification.showFailureMessage('Failed to delete accessRule');
            }
        });
    }.bind(accessRuleFunctions);

	return accessRuleFunctions;
});