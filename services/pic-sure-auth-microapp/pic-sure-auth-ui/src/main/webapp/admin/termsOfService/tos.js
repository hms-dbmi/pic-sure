define(["backbone","handlebars", "text!termsOfService/tos.hbs", "picSure/picsureFunctions"],
    function(BB, HBS, template, picsureFunctions){
        var tosModel = BB.Model.extend({
        });

        var tosView = BB.View.extend({
            template : HBS.compile(template),
            events : {
                "click .accept-tos-button":   "acceptTOS",
            },
            acceptTOS: function () {
                picsureFunctions.acceptTOS(function(){
                    if (sessionStorage.redirection_url) {
                        window.location = sessionStorage.redirection_url;
                    }
                    else {
                        history.pushState({}, "", "userManagement");
                    }
                })
            },
            render : function(){
                picsureFunctions.getLatestTOS(function (content) {
                    this.model.set("content", content);
                    this.$el.html(this.template({content: content}));
                }.bind(this));
            }
        });

        return {
            View : tosView,
            Model: tosModel
        };
});