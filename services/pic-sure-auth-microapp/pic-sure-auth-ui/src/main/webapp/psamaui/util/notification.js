define(["Noty"],
        function(Noty){
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

    notification.showFailureMessage = function (message, timeout) {
        var defaultMessage = "Failed to perform action.";
        if(!timeout) timeout=3000;
        new Noty({
            type: "error",
            text: message ? message : defaultMessage,
            timeout: timeout
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

    notification.showConfirmationDialog = function (callback, layout, text) {
        // exit if another confirmation is currently displayed
        if ($(".noty_type__alert").length > 0) return;
        // show dialog box
        var n = new Noty({
            text: text? text: 'Do you want to continue?',
            layout: layout?layout:'topCenter',
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