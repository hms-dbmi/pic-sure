define(["Noty", "handlebars", "text!options/confirmationDialog.hbs",],
        function(Noty, HBS, confirmationDialog){
    var notification = {
        init: function () {

        }
    };
    notification.showSuccessMessage = function (message) {
        new Noty({
            type: "success",
            text: message,
            timeout: 3000
        }).show();
    }.bind(notification);

    notification.showFailureMessage = function (message) {
        var defaultMessage = "Failed to perform action.";
        new Noty({
            type: "error",
            text: message ? message : defaultMessage,
            timeout: 3000
        }).show();
    }.bind(notification);

    notification.showWarningMessage = function (message) {
        var defaultMessage = "Sorry, can't perform this action.";
        new Noty({
            type: "warning",
            text: message ? message : defaultMessage,
            timeout: 3000
        }).show();
    }.bind(notification);

    notification.showConfirmationDialog = function (callback) {
        var n = new Noty({
            text: 'Do you want to continue?',
            layout: 'topCenter',
            buttons: [
                Noty.button('YES', 'btn btn-info', function () {
                    n.close();
                    callback();
                }),
                Noty.button('NO', 'btn btn-danger btn-right', function () {
                    n.close();
                })
            ]
        }).show();

    }.bind(notification);

	return notification;
});