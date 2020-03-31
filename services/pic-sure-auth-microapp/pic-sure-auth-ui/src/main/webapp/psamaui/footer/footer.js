define(["backbone","handlebars", "text!footer/footer.hbs", "overrides/footer", "text!../settings/settings.json"], 
		function(BB, HBS, template, overrides, settings){
	var footerView = BB.View.extend({
		initialize : function(){
			this.template = HBS.compile(template);
			this.settings = JSON.parse(settings);
		},
		render : typeof overrides.render === 'function' ? overrides.render : function(){
			this.$el.html(this.template({ footerMessage : this.settings.footerMessage }));
		}
	});

	return {
		View : new footerView({})
	};
});