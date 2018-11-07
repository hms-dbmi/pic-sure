define(["util/notification"],
    function(notification){
        var tosFunctions = {
            init: function () {}
        };

        tosFunctions.getLatestTOS = function (callback) {
            $.ajax({
                url: window.location.origin + "/auth/tos/latest",
                type: 'GET',
                contentType: 'application/json',
                success: function(response){
                    callback(response);
                },
                error: function(response){
                    notification.showFailureMessage("Failed to load Terms of Service.");
                }
            });
        }.bind(tosFunctions);

        tosFunctions.acceptTOS = function (callback) {
            $.ajax({
                url: window.location.origin + "/auth/tos/accept",
                type: 'POST',
                contentType: 'application/json',
                success: function(response){
                    callback(response);
                },
                error: function(response){
                    notification.showFailureMessage("Failed to accept Terms of Service.");
                }
            });
        }.bind(tosFunctions);

        return tosFunctions;
    });